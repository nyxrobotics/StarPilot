"""A route the car is not on must not be asked for again every two seconds (bench finding BC-04).

The X10 snaps the start of a route to the road network (up to 300 m, 141 m on the bench) and the comma accepts a start up to
1,000 m from where it asked, but its own off-route check calls a car more than 40 m from the route off route, even at
standstill. Every such route used to be dropped two seconds after it arrived and was asked for again at once: 34 requests in
71 s, each 3 routes, 176 KB and 0.5 - 0.7 s of X10 time.

The world of these tests is one straight road along the equator, 1,112 m long, the destination at its east end. The fake X10
answers every request with the road from the point of it nearest to where the car asked, so a car that stands 141 m north of
the road is 141 m from its route whatever the comma does. Time is simulated: one update per second, no sleeping.
"""
from concurrent.futures import Future
import math

import pytest

from openpilot.starpilot.navigation.owner import NavigationOwner
from openpilot.starpilot.navigation.route_engine import EARTH_RADIUS, NavigationRoute, distance
from openpilot.starpilot.navigation.runtime import (OFF_ROUTE_DROP_NS, START_HOLD_M, START_RETRY_MAX_NS, START_RETRY_NS,
                                                    RouteRuntime)

SECOND = 1_000_000_000
METERS_PER_DEGREE = EARTH_RADIUS * math.pi / 180  # one degree of latitude, and of longitude on the equator
DESTINATION = {'name': 'Library', 'longitude': .01, 'latitude': 0.}


def road_route(position):
  """The X10's answer: the road (latitude 0) from the point of it nearest to `position` to the destination."""
  length = distance((position[0], 0.), (DESTINATION['longitude'], 0.))
  return NavigationRoute({
    'distance': length, 'duration': length / 10,
    'geometry': {'coordinates': [[position[0], 0.], [DESTINATION['longitude'], 0.]]},
    'legs': [{'steps': [{'distance': length, 'duration': length / 10, 'maneuver': {'type': 'depart', 'instruction': 'Head east'}},
                        {'distance': 0., 'duration': 0., 'maneuver': {'type': 'arrive', 'instruction': 'You have arrived'}}]}]})


class X10:
  """The route API: remembers when (simulated seconds) and from where it was asked."""

  def __init__(self):
    self.now = 0
    self.requests = []

  def fetch(self, token, position, destination, bearing=None, exclusions=()):
    self.requests.append((self.now, position))
    return road_route(position)

  @property
  def times(self):
    return [when for when, _position in self.requests]


class Immediate:
  """Answers when asked; the runtime takes the answer at its next update, like a route server that is quick."""

  def submit(self, function, *args):
    future = Future()
    future.set_result(function(*args))
    return future


@pytest.fixture
def world(tmp_path, monkeypatch):
  monkeypatch.setenv('SIDECAR_CONFIG', str(tmp_path / 'no-sidecar.json'))
  monkeypatch.setattr(NavigationOwner, '_is_metric', lambda self: True)
  owner = NavigationOwner(tmp_path, runtime_source=lambda: None, transient_root=tmp_path / 'runtime')
  settings = owner.configure({'enabled': True, 'token': 'pk.test'}, '0', True)
  owner.select(DESTINATION, settings['revision'], True)
  x10 = X10()
  return RouteRuntime(owner, engine=x10, executor=Immediate()), x10


def drive(world, seconds, car, since=0):
  """One update per simulated second, from second `since`. car(t) -> (meters north of the road, meters east of its start, speed m/s, bearing)."""
  runtime, x10 = world
  results = []
  for t in range(since, since + seconds):
    north, east, speed, bearing = car(t)
    x10.now = t
    now = (10 + t) * SECOND
    results.append(runtime.update(now, (now - 1_000_000, (east / METERS_PER_DEGREE, north / METERS_PER_DEGREE), speed, bearing), 1))
  return results


def parked(north, east=0.):
  return lambda t: (north, east, 0., None)


def test_a_car_parked_off_the_road_network_asks_the_x10_once_not_every_two_seconds(world):
  runtime, x10 = world
  results = drive(world, 71, parked(141.))  # the length of the bench run
  assert x10.times == [0], f'{len(x10.requests)} requests in 71 s (34 on the bench and 35 here before the fix)'
  assert not any(result['controlValid'] for result in results), 'a route the car is not on never gives it control'
  assert results[-1]['status'] == 'guiding' and results[-1]['route'], 'the route stays on display'
  assert runtime.route is not None


def test_gps_jitter_around_the_parking_spot_keeps_the_route(world):
  _runtime, x10 = world
  wander = START_HOLD_M * .4  # a circle of 40 m around the spot: fixes up to 80 m apart
  drive(world, 120, lambda t: (141. + wander * math.sin(t), wander * math.cos(t), 0., None))
  assert x10.times == [0]


def test_a_car_that_drives_away_from_where_it_asked_gets_a_new_route_after_the_pause(world):
  _runtime, x10 = world
  # 141 m north of the road and rolling east along it at 20 m/s: the X10 snaps every answer away from the car again.
  drive(world, 300, lambda t: (141., 20. * t, 20., 90.))
  assert x10.times == [0, 10, 30, 70, 130, 190, 250]
  gaps = [b - a for a, b in zip(x10.times, x10.times[1:], strict=False)]
  assert gaps == [10, 20, 40, 60, 60, 60], 'the pause doubles with every repeat, from 10 s up to one minute'
  assert START_RETRY_NS == 10 * SECOND and START_RETRY_MAX_NS == 60 * SECOND


def test_a_new_destination_starts_from_scratch(world):
  runtime, x10 = world
  def car(t):
    return 141., 20. * t, 20., 90.

  drive(world, 80, car)
  assert x10.times == [0, 10, 30, 70], 'the pause has grown to 40 s'
  owner = runtime.owner
  owner.select({'name': 'Elsewhere', 'longitude': .02, 'latitude': 0.}, owner.read()['revision'], True)
  drive(world, 60, car, since=80)
  assert x10.times[4:] == [80, 90, 110], f'{x10.times}: asked at once, then the pause starts again at 10 s'


def test_reaching_a_route_starts_the_pauses_again(world):
  _runtime, x10 = world

  def car(t):
    if t < 30 or 50 <= t:
      return 141., 20. * t, 20., 90.  # 141 m from the road: every answer is snapped away from the car
    return 0., 20. * t, 20., 90.      # on the road from second 30 to 50

  results = drive(world, 70, car)
  assert x10.times[:3] == [0, 10, 30], 'two routes in a row that the car never reached: the pause is 20 s by now'
  assert results[31]['controlValid'] and results[49]['controlValid'], 'the third answer is on the road'
  left = next(t for t, result in enumerate(results) if t > 31 and not result['controlValid'])
  assert left == 50 and results[left + 2]['status'] == 'routing'
  assert x10.times[3:] == [53, 63], f'{x10.times}: asked at once after leaving a route it was on, then a pause of 10 s again, not 40 s'


def test_a_new_route_starts_its_own_off_route_clock(world):
  runtime, _x10 = world
  drive(world, 1, parked(141.))
  runtime.off_route_since = 1  # what a route that was dropped a moment ago leaves behind: a clock that started long ago
  drive(world, 1, parked(141.), since=1)  # takes the answer: off route, from this update on
  assert runtime.off_route_since == 11 * SECOND


def test_the_pause_does_not_apply_to_a_route_the_car_was_on(world):
  runtime, x10 = world

  def car(t):  # on the road for six seconds, then 500 m north of it: a real departure from a route the car was on
    return (0., 0., 0., None) if t < 6 else (500., 0., 0., None)

  results = drive(world, 9, car)
  assert results[5]['controlValid'] and not results[6]['controlValid']
  dropped = len(results) - 1
  assert dropped == 6 + OFF_ROUTE_DROP_NS // SECOND and results[dropped]['status'] == 'routing', 'dropped two seconds after the car left it'
  assert runtime.route is None and runtime.off_route_since == 0, 'the dropped route takes its clock with it'
  results += drive(world, 11, car, since=9)
  assert x10.times == [0, dropped + 1], 'asked again at the very next update, as before the fix'
  # The new route starts 500 m from the car: that is the parked-off-the-network case again, now held.
  assert results[-1]['status'] == 'guiding' and not results[-1]['controlValid'] and runtime.route is not None


def test_a_car_that_pulls_off_the_road_close_to_where_it_asked_still_loses_its_route(world):
  runtime, x10 = world

  def car(t):  # on the road for six seconds, then 60 m north of it (a lot beside the road): within 100 m of where it asked
    return (0., 0., 0., None) if t < 6 else (60., 0., 0., None)

  results = drive(world, 12, car)
  dropped = next(t for t, result in enumerate(results) if t > 1 and result['status'] == 'routing')
  assert dropped == 8 and x10.times == [0, 9], 'a route the car was on is never held: dropped after two seconds, asked again at once'
  assert runtime.route_near is False and runtime.route is not None, 'the new route starts 60 m from the car: held'


def test_a_moving_car_that_turns_off_the_route_is_rerouted_after_two_seconds_without_a_pause(world):
  _runtime, x10 = world
  # East along the road at 10 m/s for 20 s (on the route), then north (a different road).
  results = drive(world, 40, lambda t: (0., 10. * t, 10., 90.) if t < 20 else (10. * (t - 20), 200., 10., 0.))
  assert all(result['controlValid'] for result in results[1:20])
  left = next(t for t, result in enumerate(results) if t > 1 and not result['controlValid'])
  dropped = next(t for t, result in enumerate(results) if t > 1 and result['status'] == 'routing')
  assert left == 20 and dropped == left + 2
  assert x10.times[:2] == [0, dropped + 1]


def test_every_new_route_gets_its_own_two_seconds_before_it_is_dropped(world):
  _runtime, x10 = world
  # On the road but driving west, against the route: near it, never aligned with it.
  drive(world, 40, lambda t: (0., 600. - 5. * t, 5., 270.))
  assert x10.times[0] == 0
  gaps = [b - a for a, b in zip(x10.times, x10.times[1:], strict=False)]
  assert gaps and min(gaps) >= 4, f'gaps {gaps}: one update to take the answer, two seconds off route, one update to ask again'


def test_a_car_that_drives_to_the_road_from_a_driveway_joins_the_route_and_gets_control(world):
  runtime, x10 = world
  # 141 m north of the road, rolling south-east at 5 m/s (within reach, 50 m, of the route after 128 m), onto the road and east along it.
  crossing = 141. / (5. * math.cos(math.pi / 4))

  def car(t):
    if t < crossing:
      return 141. - 5. * math.cos(math.pi / 4) * t, 5. * math.sin(math.pi / 4) * t, 5., 135.
    return 0., 141. + 5. * (t - crossing), 5., 90.

  results = drive(world, 90, car)
  # Held for 100 m of rolling, then asked again from the new place: two requests (13 before the fix, one every 2 s until within reach).
  assert x10.times == [0, 22], x10.times
  first = next(t for t, result in enumerate(results) if result['controlValid'])
  assert 40 < car(first)[0] <= 50, f'control only once the car is within 50 m of the route, not before ({car(first)[0]:.0f} m)'
  assert all(result['controlValid'] for result in results[first:])
  assert runtime.route_near


def test_a_car_that_reaches_the_route_sideways_gets_two_seconds_to_line_up_with_it(world):
  _runtime, x10 = world
  # Held for 18 s while rolling south at 5 m/s toward the road, within reach of it at 19 s, heading south (against the route) for two
  # more seconds, then east: off route for the whole time, but only the last two seconds count as off a route it was on.
  def car(t):
    if t < 21:
      return 141. - 5. * t, 0., 5., 180.
    return 141. - 5. * 21, 5. * (t - 21), 5., 90.
  results = drive(world, 40, car)
  assert x10.times == [0], f'{x10.times}: the clock of the approach must not drop the route the moment the car reaches it'
  assert not results[19]['controlValid'] and not results[20]['controlValid'] and results[22]['controlValid']


def test_progress_tells_near_the_route_from_aligned_with_it():
  route = road_route((0., 0.))
  on_road = route.progress((.004, 0.), 5., 90.)
  assert on_road.near_route and not on_road.off_route
  against = route.progress((.004, 0.), 5., 270.)
  assert against.near_route and against.off_route, 'on the road but heading the other way'
  parked_off = route.progress((.004, 141. / METERS_PER_DEGREE), 0., None)
  assert not parked_off.near_route and parked_off.off_route
  assert route.progress((.004, 39. / METERS_PER_DEGREE), 0., None).near_route
  assert not route.progress((.004, 41. / METERS_PER_DEGREE), 0., None).near_route, '40 m at standstill'
