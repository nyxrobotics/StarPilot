"""Bounded LAN route transport and validation for the opt-in X10 sidecar."""
from __future__ import annotations

import http.client
import json
import math
import time
from itertools import pairwise
from typing import Any, NamedTuple
from urllib.parse import urlencode, urlsplit

from openpilot.starpilot.navigation.owner import ValidationError
from openpilot.starpilot.navigation.route_engine import EARTH_RADIUS, MapboxRouteEngine, NavigationRoute
from openpilot.starpilot.navigation.sidecar_config import (
  SOURCE_FIXED, SOURCE_VERIFIED, DeadlineSocket, RouteServer, invalidate_discovery, is_lan_address,
  sidecar_enabled, sidecar_route_server,
)

ERROR_LOG_INTERVAL_S = 30.0
NO_SERVER_MESSAGE = "sidecar: no route server (sidecar off, unusable host setting, or no gateway and nothing answered GET /status)"

# --- the exchange with the route server (RouteSession)
ROUTE_DEADLINE_S = 5.0                # one route request in total: connect, request and the whole answer (upstream's timeout=5 is per socket operation)
MAX_ROUTE_BYTES = 16 * 1024 * 1024    # hard cap of an answer; the largest real one seen is 0.4 MB
READ_CHUNK_BYTES = 64 * 1024
ROUTE_HEADERS = {"Accept": "application/json", "Accept-Encoding": "identity", "Connection": "close", "User-Agent": "starpilot-sidecar-route/3"}

# --- what a route may contain (check_route_document). Real routes: <= 3,163 vertices, <= 28 steps, <= 2 banners, <= 7 lanes, <= 215 characters per text.
MAX_ROUTES = 3                        # a request without alternatives gets one; alt-N routes at most 3
MAX_ROUTE_VERTICES = 8000             # cap parse and 1 Hz progress work below the engine's 20,000 point limit
MAX_ROUTE_STEPS = 1000                # cap parse and publish work below the engine's 2,048 step limit
MAX_BANNERS_PER_STEP = 16
MAX_BANNER_COMPONENTS = 64
MAX_LANE_COMPONENTS = 16              # per banner
MAX_LANE_DIRECTIONS = 16              # per lane
MAX_TEXT_CHARS = 512                  # every text that ends up in a navInstruction message (the whole message stays below 11 KB)
MAX_ENDPOINT_DISTANCE_M = 1000.0      # first vertex to the requested start, last vertex to the requested destination (the X10 refuses snaps over 300 m)
MAX_MANEUVER_OFF_ROUTE_M = 100.0      # a maneuver location to the nearest vertex (Valhalla puts it exactly on one)
DISTANCE_RATIO_MIN = 0.5              # route / step distances against the haversine length of the geometry
DISTANCE_RATIO_MAX = 3.0
SHORT_ROUTE_M = 10.0                  # below this length a ratio means nothing: the distances may then be at most SHORT_ROUTE_DISTANCE_MAX_M
SHORT_ROUTE_DISTANCE_MAX_M = 100.0
MIN_LENGTH_FOR_DURATION_CHECK_M = 100.0
MAX_AVERAGE_SPEED_MS = 100.0          # a route whose duration implies more than this is absurd (real: <= 26 m/s)
MAX_QUANTITY = 1e9                    # distances (m), durations (s)
MAX_SPEED_LIMIT = 400.0               # maxspeed entries (km/h or mph)
SPEED_UNITS = ("km/h", "mph")
MESSAGE_BYTES_LIMIT = 64 * 1024       # navInstruction / navRoute stay below this (msgq aborts above ~85 KB); 8,000 vertices are 64,072 bytes
METERS_PER_DEGREE = EARTH_RADIUS * math.pi / 180.0


class SidecarUnavailable(RuntimeError):
  """No usable route server address right now (sidecar off, no Wi-Fi gateway, bad config)."""


class RouteFetchError(ValidationError):
  """The LAN route server could not deliver a usable answer; messages contain no request URL or token."""


class RouteRejected(RouteFetchError):
  """The answer is valid JSON but not a route that is safe to follow (check_route_document)."""


def describe_route_server(server: RouteServer) -> str:
  """One log line telling how the route server was chosen."""
  address = f"{server.host}:{server.port}"
  if server.source == SOURCE_FIXED:
    return f"sidecar: route server {address} (fixed in the config)"
  if server.source == SOURCE_VERIFIED:
    return f"sidecar: route server {address} (answered GET /status)"
  return f"sidecar: none of {server.probed} candidates answered GET /status, using the unverified default gateway {address}"


# ---------------------------------------------------------------- the exchange: no redirect, no compression, a size cap, one deadline

def _refuse_constant(name: str) -> Any:
  raise ValueError(f"{name} is not a JSON number")


def _parse_float(text: str) -> float:
  number = float(text)
  if not math.isfinite(number):  # "1e999" parses to inf
    raise ValueError("number out of range")
  return number


class RouteResponse:
  """A bounded HTTP response with strict JSON decoding."""

  def __init__(self, status_code: int, body: bytes):
    self.status_code = status_code
    self._body = body

  def json(self) -> Any:
    try:
      return json.loads(self._body.decode("utf-8"), parse_constant=_refuse_constant, parse_float=_parse_float)
    except (ValueError, RecursionError):  # not UTF-8, not JSON, NaN / Infinity / 1e999, or nested too deep
      raise RouteFetchError("the route answer is not valid JSON") from None


def _declared_length(header: str | None) -> int | None:
  text = (header or "").strip()
  return int(text) if text.isascii() and text.isdigit() and len(text) <= 12 else None


class _Answer(NamedTuple):
  status: int
  encoding: str
  body: bytes | None  # None: not read (non-200, compressed, or larger than MAX_ROUTE_BYTES)
  too_large: bool


class RouteSession:
  """Bounded HTTP transport for SidecarRouteEngine.

  * a redirect is never followed (a 3xx is only a status),
  * nothing is decompressed (Accept-Encoding: identity; a Content-Encoding in the answer refuses it),
  * at most MAX_ROUTE_BYTES of body are read, however it is framed (Content-Length, chunked, until close),
  * ONE deadline covers connect, request and the whole answer (ROUTE_DEADLINE_S): a peer that answers one byte every few seconds ends the
    request on time, and no environment proxy, cookie or credential other than the access_token of the URL is ever used,
  * plain http to a LAN address only.
  Every failure is a RouteFetchError caught by RouteRuntime as an unavailable route.
  """

  def get(self, url: str, params: dict[str, str] | None = None, timeout: float | None = None) -> RouteResponse:
    limit = ROUTE_DEADLINE_S
    if isinstance(timeout, (int, float)) and not isinstance(timeout, bool) and timeout > 0:
      limit = min(float(timeout), ROUTE_DEADLINE_S)
    parts = urlsplit(url)
    host = parts.hostname
    if parts.scheme != "http" or host is None or not is_lan_address(host):
      raise RouteFetchError("route requests go to http://<LAN address> only")
    try:
      port = parts.port or 80
    except ValueError:
      raise RouteFetchError("the route server port is not valid") from None
    query = urlencode(params) if params else parts.query
    target = (parts.path or "/") + (f"?{query}" if query else "")

    try:
      answer = self._exchange(host, port, target, time.monotonic() + limit, limit)
    except TimeoutError:
      raise RouteFetchError("the route request timed out") from None
    except (OSError, http.client.HTTPException) as error:
      raise RouteFetchError(f"the route request failed ({type(error).__name__})") from None
    if answer.status != 200:
      return RouteResponse(answer.status, b"")
    if answer.encoding:
      raise RouteFetchError("the route server compressed its answer: only plain answers are accepted")
    if answer.too_large or answer.body is None:
      raise RouteFetchError(f"the route answer is larger than {MAX_ROUTE_BYTES} bytes")
    return RouteResponse(200, answer.body)

  @staticmethod
  def _exchange(host: str, port: int, target: str, deadline: float, limit: float) -> _Answer:
    connection = http.client.HTTPConnection(host, port)
    try:
      connection.sock = DeadlineSocket(deadline, operation_timeout=limit)  # request() skips connect() when a socket is set
      connection.sock.connect((host, port))
      connection.request("GET", target, headers=ROUTE_HEADERS)
      response = connection.getresponse()
      if response.status != 200:
        return _Answer(response.status, "", None, False)
      encoding = (response.getheader("Content-Encoding") or "").strip().lower()
      if encoding and encoding != "identity":
        return _Answer(200, encoding, None, False)
      declared = _declared_length(response.getheader("Content-Length"))
      if declared is not None and declared > MAX_ROUTE_BYTES:
        return _Answer(200, "", None, True)
      body = bytearray()
      while True:
        chunk = response.read(READ_CHUNK_BYTES)
        if not chunk:
          break
        body += chunk
        if len(body) > MAX_ROUTE_BYTES:
          return _Answer(200, "", None, True)
      if declared is not None and len(body) != declared:
        raise http.client.IncompleteRead(bytes(body), declared - len(body))
      return _Answer(200, "", bytes(body), False)
    finally:
      connection.close()


# ---------------------------------------------------------------- the route check

def _text(value: Any, what: str) -> None:
  """`value` must be a str that every consumer can take: encodable as UTF-8 (a JSON "\\ud83d" escape is a lone surrogate that makes
  the capnp assignment raise), without NUL, and short enough for the messages navigationd publishes."""
  if type(value) is not str:
    raise RouteRejected(f"{what} is not a string")
  if len(value) > MAX_TEXT_CHARS:
    raise RouteRejected(f"{what} has {len(value)} characters (limit {MAX_TEXT_CHARS})")
  if "\x00" in value:
    raise RouteRejected(f"{what} contains a NUL character")
  try:
    value.encode("utf-8")
  except UnicodeEncodeError:
    raise RouteRejected(f"{what} is not valid Unicode (lone surrogate)") from None


def _optional_text(value: Any, what: str) -> None:
  if value is not None:  # upstream treats a missing and a null field alike
    _text(value, what)


def _quantity(value: Any, what: str, maximum: float = MAX_QUANTITY) -> float:
  """A finite number in [0, maximum] (a JSON number only: no bool, no string)."""
  if type(value) not in (int, float):
    raise RouteRejected(f"{what} is not a number")
  try:
    number = float(value)
  except OverflowError:
    raise RouteRejected(f"{what} is out of range") from None
  if not 0.0 <= number <= maximum:  # False for NaN and for infinity too
    raise RouteRejected(f"{what} is not a finite number between 0 and {maximum:g}")
  return number


def _degrees(value: Any, limit: float, what: str) -> float:
  if type(value) not in (int, float):
    raise RouteRejected(f"{what} is not a number")
  try:
    number = float(value)
  except OverflowError:
    raise RouteRejected(f"{what} is out of range") from None
  if not math.isfinite(number) or abs(number) > limit:
    raise RouteRejected(f"{what} is not a coordinate")
  return number


def _position(value: Any, what: str) -> tuple[float, float]:
  """(latitude, longitude) of a GeoJSON position [longitude, latitude, ...]."""
  if type(value) not in (list, tuple) or len(value) < 2:
    raise RouteRejected(f"{what} is not a position")
  return _degrees(value[1], 90.0, f"{what} latitude"), _degrees(value[0], 180.0, f"{what} longitude")


def _haversine(a: tuple[float, float], b: tuple[float, float]) -> float:
  """Meters between two (latitude, longitude) points; infinite when a value is not finite."""
  if not all(math.isfinite(value) for value in (*a, *b)):
    return math.inf
  dlat = math.radians(b[0] - a[0])
  dlon = math.radians(b[1] - a[1])
  h = math.sin(dlat / 2.0) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2.0) ** 2
  return 2.0 * math.asin(math.sqrt(min(1.0, h))) * EARTH_RADIUS


def _check_banner(banner: Any, where: str) -> None:
  """Reject malformed banner fields before NavigationRoute reads the primary instruction."""
  if not isinstance(banner, dict):
    raise RouteRejected(f"{where} is not an object")
  _quantity(banner.get("distanceAlongGeometry"), f"{where} distanceAlongGeometry")
  primary = banner.get("primary")
  if not isinstance(primary, dict):
    raise RouteRejected(f"{where} has no primary")
  for key in ("text", "type", "modifier"):
    _optional_text(primary.get(key), f"{where} primary {key}")
  secondary = banner.get("secondary")
  if secondary is not None:
    if not isinstance(secondary, dict):
      raise RouteRejected(f"{where} secondary is not an object")
    _text(secondary.get("text"), f"{where} secondary text")
  sub = banner.get("sub")
  if sub is None:
    return
  components = sub.get("components") if isinstance(sub, dict) else None
  if type(components) is not list or len(components) > MAX_BANNER_COMPONENTS:
    raise RouteRejected(f"{where} sub has no components list of at most {MAX_BANNER_COMPONENTS}")
  lanes = 0
  for component in components:
    if not isinstance(component, dict):
      raise RouteRejected(f"{where} has a component that is not an object")
    _text(component.get("type"), f"{where} component type")
    if component["type"] != "lane":
      continue
    lanes += 1
    if lanes > MAX_LANE_COMPONENTS:
      raise RouteRejected(f"{where} has more than {MAX_LANE_COMPONENTS} lane components")
    if type(component.get("active")) is not bool:
      raise RouteRejected(f"{where} has a lane whose active is not true / false")
    directions = component.get("directions")
    if type(directions) is not list or len(directions) > MAX_LANE_DIRECTIONS:
      raise RouteRejected(f"{where} has a lane without a directions list of at most {MAX_LANE_DIRECTIONS}")
    for direction in directions:
      _text(direction, f"{where} lane direction")
    _optional_text(component.get("active_direction"), f"{where} lane active_direction")


def _check_magnitude(value: float, length: float, what: str) -> None:
  """`value` (a distance in meters) against the haversine `length` of the geometry it describes."""
  if length < SHORT_ROUTE_M:
    if value > SHORT_ROUTE_DISTANCE_MAX_M:
      raise RouteRejected(f"{what} is {value:.0f} m for a geometry of {length:.0f} m")
  elif not DISTANCE_RATIO_MIN * length <= value <= DISTANCE_RATIO_MAX * length:
    raise RouteRejected(f"{what} is {value:.0f} m for a geometry of {length:.0f} m")


def _check_leg(leg: Any, points: list[tuple[float, float]], length: float) -> None:
  if not isinstance(leg, dict):
    raise RouteRejected("the leg is not an object")
  steps = leg.get("steps")
  if type(steps) is not list or not steps:
    raise RouteRejected("the leg has no steps")
  if len(steps) > MAX_ROUTE_STEPS:
    raise RouteRejected(f"the leg has {len(steps)} steps (limit {MAX_ROUTE_STEPS})")
  total = 0.0
  locations: list[tuple[float, float]] = []
  for index, step in enumerate(steps):
    where = f"step {index}"
    if not isinstance(step, dict):
      raise RouteRejected(f"{where} is not an object")
    total += _quantity(step.get("distance"), f"{where} distance")
    _quantity(step.get("duration"), f"{where} duration")
    maneuver = step.get("maneuver")
    if not isinstance(maneuver, dict):
      raise RouteRejected(f"{where} has no maneuver")
    _text(maneuver.get("type"), f"{where} maneuver type")
    _text(maneuver.get("instruction"), f"{where} instruction")
    _optional_text(maneuver.get("modifier"), f"{where} maneuver modifier")
    locations.append(_position(maneuver.get("location"), f"{where} location"))
    banners = step.get("bannerInstructions")
    if banners is not None:
      if type(banners) is not list or len(banners) > MAX_BANNERS_PER_STEP:
        raise RouteRejected(f"{where} has no bannerInstructions list of at most {MAX_BANNERS_PER_STEP}")
      for number, banner in enumerate(banners):
        _check_banner(banner, f"{where} banner {number}")
  _check_magnitude(total, length, "the sum of the step distances")

  # A maneuver far from every route vertex could announce a turn at the wrong place.
  for index, (latitude, longitude) in enumerate(locations):
    east_scale = METERS_PER_DEGREE * math.cos(math.radians(latitude))
    near_route = False
    for point in points:
      north = (point[0] - latitude) * METERS_PER_DEGREE
      east = ((point[1] - longitude + 180.0) % 360.0 - 180.0) * east_scale
      if north * north + east * east <= MAX_MANEUVER_OFF_ROUTE_M**2:
        near_route = True
        break
    if not near_route:
      raise RouteRejected(f"step {index} is not on the route (location more than {MAX_MANEUVER_OFF_ROUTE_M:.0f} m from every vertex)")

  if "annotation" not in leg:
    return
  annotation = leg["annotation"]
  if not isinstance(annotation, dict):
    raise RouteRejected("the annotation is not an object")
  if "maxspeed" not in annotation:
    return
  maxspeed = annotation["maxspeed"]
  if type(maxspeed) is not list or (maxspeed and len(maxspeed) != len(points) - 1):
    raise RouteRejected(f"maxspeed does not have one entry per segment ({len(points) - 1})")
  for item in maxspeed:
    if not isinstance(item, dict):
      raise RouteRejected("a maxspeed entry is not an object")
    _quantity(item.get("speed"), "a maxspeed speed", MAX_SPEED_LIMIT)
    if item.get("unit") not in SPEED_UNITS:
      raise RouteRejected("a maxspeed entry has no km/h or mph unit")


def _check_endpoint(vertex: tuple[float, float], requested: tuple[float, float], verb: str, what: str) -> None:
  gap = _haversine(vertex, requested)
  if not gap <= MAX_ENDPOINT_DISTANCE_M:  # a NaN request is refused too
    raise RouteRejected(f"the route {verb} {gap / 1000.0:.1f} km from the requested {what} (limit {MAX_ENDPOINT_DISTANCE_M / 1000.0:.0f} km)")


def _check_route(route: Any, start: tuple[float, float], destination: tuple[float, float]) -> None:
  if not isinstance(route, dict):
    raise RouteRejected("the route is not an object")
  distance = _quantity(route.get("distance"), "the route distance")
  duration = _quantity(route.get("duration"), "the route duration")
  geometry = route.get("geometry")
  coordinates = geometry.get("coordinates") if isinstance(geometry, dict) else None
  if type(coordinates) is not list or len(coordinates) < 2:
    raise RouteRejected("the route geometry has fewer than 2 vertices")
  if len(coordinates) > MAX_ROUTE_VERTICES:
    raise RouteRejected(
      f"the route has {len(coordinates)} vertices (limit {MAX_ROUTE_VERTICES}). Choose a nearer destination",
    )
  points = [_position(coordinate, "a geometry vertex") for coordinate in coordinates]
  length = sum(_haversine(a, b) for a, b in pairwise(points))
  _check_endpoint(points[0], start, "starts", "start")
  _check_endpoint(points[-1], destination, "ends", "destination")
  _check_magnitude(distance, length, "the route distance")
  if length >= MIN_LENGTH_FOR_DURATION_CHECK_M and duration * MAX_AVERAGE_SPEED_MS < length:
    raise RouteRejected(f"the route duration of {duration:.0f} s is absurd for {length:.0f} m")
  legs = route.get("legs")
  if type(legs) is not list or len(legs) != 1:
    raise RouteRejected("the route does not have exactly one leg")  # only legs[0] is read: more would be followed only in part
  _check_leg(legs[0], points, length)


def check_route_document(data: Any, start: tuple[float, float], destination: tuple[float, float]) -> None:
  """Validate every route of an X10 Mapbox-shaped answer before NavigationRoute parses it.

  The caps cover text encoding, geometry and step sizes, route endpoints, maneuver proximity,
  plausible distances and duration. Empty or non-Ok answers are rejected by the caller.
  """
  if not isinstance(data, dict) or data.get("code") != "Ok":
    return
  routes = data.get("routes")
  if routes is None or routes == []:
    return
  if type(routes) is not list or len(routes) > MAX_ROUTES:
    raise RouteRejected(f"routes is not a list of at most {MAX_ROUTES}")
  for route in routes:
    _check_route(route, start, destination)




class SidecarRouteEngine(MapboxRouteEngine):
  """Fetch Mapbox-shaped routes from the configured LAN host, then validate before publishing."""

  def __init__(self, session=None):
    super().__init__(session or RouteSession())
    self._last_log = {}

  def _warn(self, kind: str, message: str) -> None:
    now = time.monotonic()
    if now - self._last_log.get(kind, float('-inf')) >= ERROR_LOG_INTERVAL_S:
      self._last_log[kind] = now
      try:
        from openpilot.common.swaglog import cloudlog
        cloudlog.warning(message if message.startswith('sidecar:') else 'sidecar: ' + message)
      except Exception:
        # Logging must never turn a rejected route into an uncaught worker exception.
        pass

  def fetch(self, token: str, position, destination: dict, bearing: float | None = None,
            exclusions: tuple[str, ...] = ()) -> NavigationRoute:
    try:
      if not sidecar_enabled():
        raise SidecarUnavailable('sidecar mode is off')
      server = sidecar_route_server()
      if server is None:
        raise SidecarUnavailable('no LAN route server address')
      self._warn('server:' + server.host, describe_route_server(server))
      start = (float(position[1]), float(position[0]))
      end = (float(destination['latitude']), float(destination['longitude']))
      url = (server.directions_url + '/' +
             f'{position[0]},{position[1]};{destination["longitude"]},{destination["latitude"]}')
      params = {'access_token': token, 'geometries': 'geojson', 'steps': 'true', 'overview': 'full',
                'banner_instructions': 'true', 'alternatives': 'true'}
      if exclusions:
        params['exclude'] = ','.join(exclusions)
      if bearing is not None:
        params['bearings'] = f'{int(bearing % 360)},90;'
      response = self.session.get(url, params=params, timeout=ROUTE_DEADLINE_S)
      if response.status_code != 200:
        raise RouteFetchError(f'the route server answered HTTP {response.status_code}')
      data = response.json()
      check_route_document(data, start, end)
      if not isinstance(data, dict) or data.get('code') != 'Ok' or not data.get('routes'):
        raise RouteRejected('the route server found no driving route')
      routes = [NavigationRoute(route) for route in data['routes'][:MAX_ROUTES]]
      routes[0].alternatives = routes
      return routes[0]
    except Exception as error:
      invalidate_discovery()
      message = (str(error) if isinstance(error, (RouteFetchError, SidecarUnavailable))
                 else f'route processing failed ({type(error).__name__})')
      self._warn(type(error).__name__, f'no route: {message}')
      raise ValidationError('LAN route unavailable') from None
