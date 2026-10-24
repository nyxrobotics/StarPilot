"""Long trips and alternative routes against a route server of the X10's kind (bench finding BC-03).

The comma always asked for alternatives. On the bench the X10 needed 3.9 - 4.4 s for trips of 350 - 500 km with them, against the
comma's total deadline of 5.0 s (connect, request and the whole answer), and refused a 501 km trip as a whole (422 RouteTooLong)
because its third alternative had 9,609 vertices, although the same request without alternatives was answered in 1.8 s. Now a trip
whose straight line is longer than ALTERNATIVES_MAX_DISTANCE_M is asked for without alternatives. The validators are untouched:
these tests also pin that an oversized alternative still refuses the whole answer, and that the deadline is still one bounded total.

The fake route server below answers like the X10 did on the bench; delays are scaled down to fractions of a second.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import threading
import time
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest

from openpilot.starpilot.navigation import sidecar
from openpilot.starpilot.navigation.owner import ValidationError
from openpilot.starpilot.navigation.route_engine import distance
from openpilot.starpilot.navigation.sidecar import (ALTERNATIVES_MAX_DISTANCE_M, MAX_ROUTE_VERTICES, RouteFetchError, RouteSession,
                                                    SidecarRouteEngine)
from openpilot.starpilot.navigation.sidecar_config import SOURCE_FIXED, RouteServer

TOKYO = (35.6812, 139.7671)     # latitude, longitude
SHIBUYA = (35.6584, 139.7028)   # 7 km
OSAKA = (34.7025, 135.4959)     # 400 km straight line, about 500 km by road
SENDAI = (38.2601, 140.8824)    # 300 km straight line
MAIN_VERTICES = 5857            # the 501 km route of the bench
OVERSIZED_VERTICES = 9609       # its third alternative


def straight_route(start, end, vertices):
  """A route of `vertices` points on the straight line start - end (latitude, longitude) with the steps a real answer has."""
  points = [[start[1] + (end[1] - start[1]) * i / (vertices - 1), start[0] + (end[0] - start[0]) * i / (vertices - 1)] for i in range(vertices)]
  length = sum(distance(a, b) for a, b in zip(points, points[1:], strict=False))
  return {'distance': length, 'duration': length / 25, 'geometry': {'type': 'LineString', 'coordinates': points},
          'legs': [{'steps': [
            {'distance': length, 'duration': length / 25, 'maneuver': {'type': 'depart', 'instruction': 'Head on', 'location': points[0]}},
            {'distance': 0., 'duration': 0., 'maneuver': {'type': 'arrive', 'instruction': 'You have arrived', 'location': points[-1]}}]}]}


class FakeX10:
  """GET /directions/...: what the X10 answered on the bench.

  alternatives=false: the main route at once. alternatives=true: after `slow_s` seconds, three routes, or, with `refuse_long`, the
  422 of the X10 build that refuses a trip as a whole when an alternative has more vertices than its cap (trips over 300 km).
  """

  def __init__(self, slow_s=0., refuse_long=True, oversized_alternative=False):
    self.requests = []
    self.stop = threading.Event()
    owner = self

    class Handler(BaseHTTPRequestHandler):
      def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        (start, end) = [tuple(float(v) for v in pair.split(',')) for pair in url.path.rsplit('/', 1)[1].split(';')]
        start, end = (start[1], start[0]), (end[1], end[0])  # the path is longitude,latitude
        alternatives = query['alternatives'][0]
        owner.requests.append({'alternatives': alternatives, 'token': query.get('access_token', [''])[0], 'at': time.monotonic()})
        long = distance((start[1], start[0]), (end[1], end[0])) > 300_000
        if alternatives == 'true':
          owner.stop.wait(slow_s)
          if long and refuse_long:
            self.answer(422, {'code': 'RouteTooLong', 'message': f'route 3 has {OVERSIZED_VERTICES} geometry vertices, more than the 8000 ...'})
            return
        vertices = MAIN_VERTICES if long else 120
        routes = [straight_route(start, end, vertices)]
        if alternatives == 'true':
          routes += [straight_route(start, end, vertices + 7), straight_route(start, end, OVERSIZED_VERTICES if oversized_alternative else vertices + 13)]
        self.answer(200, {'code': 'Ok', 'routes': routes})

      def answer(self, status, document):
        body = json.dumps(document).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)

      def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass

    class Server(ThreadingHTTPServer):
      daemon_threads = True

      def handle_error(self, request, client_address):  # a client that gave up on a slow answer is the point of some tests
        pass

    self.server = Server(('127.0.0.1', 0), Handler)
    self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)

  def __enter__(self):
    self.thread.start()
    return self

  def __exit__(self, *_exc):
    self.stop.set()
    self.server.shutdown()
    self.server.server_close()
    self.thread.join(timeout=2)

  @property
  def port(self):
    return self.server.server_port


def engine_for(monkeypatch, fake):
  monkeypatch.setattr(sidecar, 'sidecar_enabled', lambda: True)
  monkeypatch.setattr(sidecar, 'sidecar_route_server', lambda: RouteServer('127.0.0.1', fake.port, SOURCE_FIXED))
  return SidecarRouteEngine(RouteSession())


def fetch(engine, start, end):
  return engine.fetch('shared-secret', (start[1], start[0]), {'name': 'there', 'latitude': end[0], 'longitude': end[1]}, 90.)


def test_a_far_trip_is_asked_without_alternatives_and_the_single_route_is_accepted(monkeypatch):
  with FakeX10() as x10:
    route = fetch(engine_for(monkeypatch, x10), TOKYO, OSAKA)
  assert [request['alternatives'] for request in x10.requests] == ['false'], 'the X10 build that refuses long trips with alternatives answered 422'
  assert len(route.alternatives) == 1 and len(route.points) == MAIN_VERTICES
  assert route.total_distance == pytest.approx(distance((TOKYO[1], TOKYO[0]), (OSAKA[1], OSAKA[0])), rel=.01)


def test_a_far_trip_that_the_x10_answers_slowly_with_alternatives_fits_the_deadline_without(monkeypatch):
  monkeypatch.setattr(sidecar, 'ROUTE_DEADLINE_S', 1.0)  # the bench: 4.05 s for a 350 km trip against 5.0 s
  with FakeX10(slow_s=1.6, refuse_long=False) as x10:
    route = fetch(engine_for(monkeypatch, x10), TOKYO, SENDAI)
  assert [request['alternatives'] for request in x10.requests] == ['false'] and len(route.alternatives) == 1


def test_a_near_trip_keeps_its_alternatives(monkeypatch):
  with FakeX10() as x10:
    route = fetch(engine_for(monkeypatch, x10), TOKYO, SHIBUYA)
  assert [request['alternatives'] for request in x10.requests] == ['true']
  assert len(route.alternatives) == 3 and route.alternatives[0] is route


def test_the_answer_of_a_slow_x10_is_still_cut_off_at_the_deadline(monkeypatch):
  monkeypatch.setattr(sidecar, 'ROUTE_DEADLINE_S', .4)
  with FakeX10(slow_s=2.0) as x10:
    started = time.monotonic()
    with pytest.raises(ValidationError, match='LAN route unavailable'):
      fetch(engine_for(monkeypatch, x10), TOKYO, SHIBUYA)
    assert .3 <= time.monotonic() - started < 1.2, 'one bounded total, whatever the trip'
  assert [request['alternatives'] for request in x10.requests] == ['true']


def test_an_oversized_alternative_still_refuses_the_whole_answer(monkeypatch):
  # The comma's validators are not relaxed: a valid main route does not rescue an answer with an alternative over the vertex cap.
  assert OVERSIZED_VERTICES > MAX_ROUTE_VERTICES >= MAIN_VERTICES
  with FakeX10(oversized_alternative=True) as x10:
    with pytest.raises(ValidationError, match='LAN route unavailable'):
      fetch(engine_for(monkeypatch, x10), TOKYO, SHIBUYA)
  assert [request['alternatives'] for request in x10.requests] == ['true']


class RecordingSession:
  def __init__(self):
    self.calls = []

  def get(self, url, params, timeout):
    self.calls.append((url, dict(params), timeout))
    raise RouteFetchError('stop here')


def straight_line_trip(meters):
  """A trip due north of TOKYO, `meters` long as the crow flies (the same haversine the comma uses)."""
  lat = TOKYO[0] + meters / (6371007.2 * math.pi / 180)
  return TOKYO, (lat, TOKYO[1])


@pytest.mark.parametrize('meters, alternatives', [
  (0., 'true'), (7_000., 'true'), (99_000., 'true'), (ALTERNATIVES_MAX_DISTANCE_M - 100, 'true'),
  (ALTERNATIVES_MAX_DISTANCE_M + 100, 'false'), (101_000., 'false'), (400_000., 'false'), (1_000_000., 'false'),
])
def test_alternatives_are_asked_for_up_to_the_straight_line_threshold_only(monkeypatch, meters, alternatives):
  monkeypatch.setattr(sidecar, 'sidecar_enabled', lambda: True)
  monkeypatch.setattr(sidecar, 'sidecar_route_server', lambda: RouteServer('192.168.1.10', 8765, SOURCE_FIXED))
  session = RecordingSession()
  start, end = straight_line_trip(meters)
  with pytest.raises(ValidationError):
    fetch(SidecarRouteEngine(session), start, end)
  (_url, params, timeout), = session.calls
  assert params['alternatives'] == alternatives
  assert timeout == sidecar.ROUTE_DEADLINE_S == 5.0, 'the deadline is not scaled: the answers fit it (1.9 - 2.7 s measured, p99 1.7 s)'


def test_the_threshold_is_the_one_the_bench_verified():
  assert ALTERNATIVES_MAX_DISTANCE_M == 100_000  # 155 routes up to 102.7 km with alternatives: all answered, the slowest in 2.65 s
