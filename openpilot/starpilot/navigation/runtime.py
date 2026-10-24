from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import math
import time
import uuid

from openpilot.starpilot.navigation.owner import ConflictError, NavigationOwner, ValidationError, ROUTE_AVOIDANCES
from openpilot.starpilot.navigation.route_engine import MapboxRouteEngine, distance

from openpilot.starpilot.gps.phone import read_phone_gps
from openpilot.starpilot.gps.source import GPS_MAX_AGE_NS, GPS_SERVICES, bearing, select_location

ARRIVED_NS = 5_000_000_000      # stopped at the destination this long shows "You have arrived"
END_ROUTE_NS = 15_000_000_000   # ...and this long ends navigation, like tapping End navigation
OFF_ROUTE_DROP_NS = 2_000_000_000  # a route the car has been off this long is dropped; one the car was on is asked for again at once

# A route whose start the car never reached. The route server snaps the start of a route to its road network (the X10 up to 300 m, the
# sidecar check accepts up to 1,000 m), so a car in a car park or a drive can stand far from the start of every route it is given.
# That is a mismatch of start and car, not a deviation: dropping the route and asking again would only get the same snapped start (bench
# finding BC-04: 34 requests in 71 s, each 0.5 - 0.7 s of X10 time). Such a route stays on display, without control, while the car stays
# where it asked; once the car has moved away, a new request waits until START_RETRY_NS after the last one, twice as long for each
# repeat, at most START_RETRY_MAX_NS. A route the car was on is never held and never delayed: leaving it is a deviation.
START_HOLD_M = 100.   # two fixes of a parked car can be 50 m apart (25 m accuracy is accepted); a car pulling out of a drive is within
                      # reach (50 m) of the start of its route after 91 m of the 141 m seen on the bench
START_RETRY_NS = 10_000_000_000
START_RETRY_MAX_NS = 60_000_000_000


def location(sm, now_ns: int, phone_fix=None):
  fix = select_location(sm, now_ns, phone_fix=phone_fix)
  if fix is None:
    return None
  stamp, gps = fix
  if not math.isfinite(gps.speed):
    return None
  return stamp, (gps.longitude, gps.latitude), max(0., gps.speed), bearing(gps)


class RouteRuntime:
  def __init__(self, owner: NavigationOwner, engine=None, executor=None, *, sidecar_mode: bool | None = None):
    self.owner = owner
    from openpilot.starpilot.navigation.sidecar_config import sidecar_enabled
    self._auto_mode = engine is None and sidecar_mode is None
    self.sidecar_mode = sidecar_enabled() if sidecar_mode is None else sidecar_mode
    if engine is None:
      engine = self._new_engine(self.sidecar_mode)
    self.engine = engine
    self.executor = executor or ThreadPoolExecutor(max_workers=1, thread_name_prefix='route')
    self.key = None
    self.route = None
    self.routes = []
    self.future = None
    self.fetch_key = None
    self.preview_only = False
    self.retry_after = 0
    self.off_route_since = 0
    self.arrived_since = 0
    self.fetch_origin = self.route_origin = None  # (longitude, latitude) the request in flight / the one behind self.route asked from
    self.fetch_at = self.route_at = 0             # ...and when it was made
    self.route_near = False                       # the car has been within reach of self.route at least once
    self.start_mismatches = 0                     # routes in a row that the car never came near
    self.ended_revision = None
    self.session = uuid.uuid4().hex

  def _new_engine(self, sidecar_mode: bool):
    if sidecar_mode:
      from openpilot.starpilot.navigation.sidecar import SidecarRouteEngine
      return SidecarRouteEngine()
    from openpilot.starpilot.navigation.mapbox_budget import MonthlyBudget
    return MapboxRouteEngine(self.owner.session, MonthlyBudget('directions', self.owner.root / 'mapbox-usage'))

  def update(self, now_ns: int, position, drive_id: int) -> dict:
    if position is not None:
      stamp, coordinates, _, bearing = position
      if 0 < stamp <= now_ns <= stamp + GPS_MAX_AGE_NS:
        self.owner.position_store.record({'longitude': coordinates[0], 'latitude': coordinates[1], 'bearing': bearing})
    if position is None:
      self.owner.position_store.flush()
    settings = self.owner.read_routing()
    from openpilot.starpilot.navigation.sidecar_config import (configured_host, sidecar_enabled, sidecar_interface,
                                                               sidecar_port, sidecar_token)
    configured_sidecar = sidecar_enabled()
    if self._auto_mode and configured_sidecar != self.sidecar_mode:
      self.sidecar_mode = configured_sidecar
      self.engine = self._new_engine(configured_sidecar)
      # The old worker may still finish; its result must never become the route for the new source.
      if self.future is not None:
        self.future.cancel()
      self.future = self.fetch_key = None
    sidecar_active = self.sidecar_mode and configured_sidecar
    token = sidecar_token() if sidecar_active else ('' if self.sidecar_mode else settings['token'])
    route_server_settings = (configured_host(), sidecar_port(), sidecar_interface()) if sidecar_active else None
    selected = settings['destination']
    destination_key = None if selected is None else (selected['id'], selected['longitude'], selected['latitude'])
    exclusions = tuple(value for key, value in ROUTE_AVOIDANCES.items() if settings[key])
    key = (settings['enabled'], sidecar_active, route_server_settings, hashlib.sha256(token.encode()).digest(),
           destination_key, drive_id, exclusions)
    if key != self.key:
      self.key, self.route = key, None
      self.routes = []
      self.preview_only = False
      self.retry_after = self.off_route_since = self.arrived_since = 0
      self.route_origin, self.route_near, self.start_mismatches = None, False, 0
    # A saved origin can draw a preview, but a fresh fix must calculate its own route.
    if position is not None and self.preview_only:
      self.route, self.routes = None, []
      self.retry_after = 0
      self.fetch_key = None  # reject an in-flight request from the saved origin
      self.preview_only = False
    base_status = ('disabled' if not settings['enabled'] else 'needsKey' if not token else
                   'noDestination' if settings['destination'] is None else 'waitingForLocation')
    result = {'sessionId': self.session, 'frameMonoTime': now_ns, 'startedMonoTime': drive_id,
                  'revision': settings['revision'], 'enabled': settings['enabled'], 'status': base_status,
                  'destinationName': (settings['destination'] or {}).get('name', ''), 'instruction': {}, 'nextManeuver': {},
                  'route': [], 'locationMonoTime': 0, 'controlValid': False}
    if self.future is not None and self.future.done():
      try:
        route = self.future.result()
      except (ValidationError, OSError, ValueError):
        route = None
      if self.fetch_key == self.key:
        self.routes = getattr(route, 'alternatives', [route]) if route is not None else []
        self.route = route
        # A new route starts its own off-route clock: the previous one's must not drop it the moment it arrives.
        self.route_origin, self.route_at, self.route_near, self.off_route_since = self.fetch_origin, self.fetch_at, False, 0
        if self.routes:
          self.owner.record_routes(settings['revision'], self.routes)
        self.retry_after = now_ns + 30_000_000_000 if route is None else 0
      self.future = None
    if self.routes:
      if getattr(self, 'routes_revision', None) != settings['revision']:
        self.owner.record_routes(settings['revision'], self.routes)
        self.routes_revision = settings['revision']
      index = settings.get('routeChoice', 0)
      selected_route = self.routes[index] if type(index) is int and 0 <= index < len(self.routes) else self.routes[0]
      if selected_route is not self.route:
        self.off_route_since = self.arrived_since = 0
      self.route = selected_route
    if base_status != 'waitingForLocation':
      return result
    if position is None:
      if self.route is not None:
        result['route'] = self.route.preview()
      else:
        saved = self.owner.position_store.read()
        if saved is not None:
          result['status'] = 'routeUnavailable' if self.retry_after > now_ns else 'routing'
          if self.future is None and now_ns >= self.retry_after:
            self.preview_only = True
            self.fetch_key = self.key
            self.fetch_origin, self.fetch_at = (saved['longitude'], saved['latitude']), now_ns
            self.future = self.executor.submit(self.engine.fetch, token,
                                               (saved['longitude'], saved['latitude']), settings['destination'], saved.get('bearing'), exclusions)
      return result
    stamp, coordinates, speed, bearing = position
    result['locationMonoTime'] = stamp
    if self.route is None:
      result['status'] = 'routeUnavailable' if self.retry_after > now_ns else 'routing'
      if self.future is None and now_ns >= self.retry_after:
        self.fetch_key = self.key
        self.fetch_origin, self.fetch_at = coordinates, now_ns
        self.future = self.executor.submit(self.engine.fetch, token, coordinates, settings['destination'], bearing, exclusions)
      return result
    progress = self.route.progress(coordinates, speed, bearing)
    if progress.near_route:
      if not self.route_near:
        self.off_route_since = 0  # the car has just reached the route: it gets its own two seconds to line up with it
      self.route_near, self.start_mismatches = True, 0
    self.off_route_since = (self.off_route_since or now_ns) if progress.off_route else 0
    self.arrived_since = (self.arrived_since or now_ns) if progress.arrived else 0
    if self.off_route_since and now_ns - self.off_route_since >= OFF_ROUTE_DROP_NS and not self._start_unreached(coordinates):
      self._drop_route()
      result['status'] = 'routing'
      return result
    arrived = self.arrived_since and now_ns - self.arrived_since >= ARRIVED_NS
    if self.arrived_since and now_ns - self.arrived_since >= END_ROUTE_NS:
      self._end_route(settings['revision'])
    result.update(status='arrived' if arrived else 'guiding', instruction=progress.instruction,
                  nextManeuver=progress.next_maneuver, route=self.route.preview(),
                  controlValid=bool(drive_id > 0 and not progress.off_route and not progress.arrived and stamp >= drive_id))
    return result

  def _start_unreached(self, coordinates) -> bool:
    """True for a route the car has never been near while the car is still where it asked for it. Never for a route it was on."""
    return not self.route_near and self.route_origin is not None and distance(coordinates, self.route_origin) <= START_HOLD_M

  def _drop_route(self) -> None:
    self.route, self.routes, self.off_route_since = None, [], 0
    if not self.route_near:
      self.start_mismatches += 1
      pause = min(START_RETRY_NS << min(self.start_mismatches - 1, 6), START_RETRY_MAX_NS)
      self.retry_after = self.route_at + pause

  def _end_route(self, revision: str) -> None:
    # Once per destination; a concurrent change (new destination, Galaxy edit) wins over arrival.
    if self.ended_revision == revision:
      return
    try:
      self.owner.clear(revision, True)
    except ConflictError:
      self.ended_revision = revision
    except (ValidationError, OSError):
      return
    else:
      self.ended_revision = revision


def phone_gps_dict(phone_fix) -> dict | None:
  if phone_fix is None:
    return None
  gps = dict(vars(phone_fix[1]))
  gps["vNED"] = list(gps.get("vNED", ()))
  return gps


def publish_status(pm, value: dict, phone_fix=None) -> None:
  from openpilot.cereal import messaging
  message = messaging.new_message('starpilotNavigation', valid=True)
  from openpilot.starpilot.navigation.wire import navigation_envelope
  message.starpilotNavigation = {**navigation_envelope(value), **({'phoneGps': gps} if (gps := phone_gps_dict(phone_fix)) else {})}
  pm.send('starpilotNavigation', message)


def main():
  from openpilot.cereal import messaging
  from openpilot.common.realtime import Ratekeeper
  from openpilot.common.swaglog import cloudlog
  owner = NavigationOwner()
  runtime = RouteRuntime(owner)
  sm = messaging.SubMaster(['deviceState', *(service for service in GPS_SERVICES if service != 'starpilotNavigation')])
  pm = messaging.PubMaster(['starpilotNavigation'])
  # Forward new phone measurements promptly; held fixes still need only a 1 Hz heartbeat.
  rate = Ratekeeper(5.)
  next_update_ns = 0
  last_phone_stamp = 0
  try:
    while True:
      sm.update(0)
      now = time.monotonic_ns()
      device = sm['deviceState']
      device_stamp = sm.logMonoTime['deviceState']
      drive_id = (device.startedMonoTime if sm.valid['deviceState'] and device.started and
                  0 < device.startedMonoTime <= device_stamp <= now <= device_stamp + 2_000_000_000 else 0)
      try:
        phone_fix = read_phone_gps(now)
        phone_stamp = phone_fix[0] if phone_fix is not None else 0
        if now >= next_update_ns or phone_stamp != last_phone_stamp:
          next_update_ns = now + 1_000_000_000
          last_phone_stamp = phone_stamp
          value = runtime.update(now, location(sm, now, phone_fix), drive_id)
          publish_status(pm, value, phone_fix)
      except (ValidationError, OSError, ValueError):
        cloudlog.warning('navigation settings or route unavailable')
      rate.keep_time()
  finally:
    owner.position_store.flush(force=True)


if __name__ == '__main__':
  main()
