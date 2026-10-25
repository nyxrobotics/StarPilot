"""Contract checks for the X10 route API on the StarPilot 7 navigation runtime."""

import copy
from concurrent.futures import Future
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time
from types import SimpleNamespace
from typing import Any

import pytest

from openpilot.starpilot.navigation.owner import NavigationOwner, ValidationError
from openpilot.starpilot.navigation.route_engine import NavigationRoute, distance
from openpilot.starpilot.navigation.runtime import RouteRuntime
from openpilot.starpilot.navigation.sidecar import (MAX_ROUTE_BYTES, RouteFetchError, RouteResponse,
                                                    RouteRejected, RouteSession, SidecarRouteEngine, check_route_document)
from openpilot.starpilot.navigation import sidecar_config
from openpilot.starpilot.navigation.sidecar_config import RouteServer, SOURCE_FIXED, sidecar_token


FIXTURE = Path(__file__).parent / 'fixtures' / 'x10_urban_shibuya.json'
START = (35.66484, 139.699228)  # latitude, longitude
END = (35.65229, 139.701592)


def route_document():
  # Produced by openpilot-sidecar's Valhalla translator from urban_shibuya_turns.response.json.
  return json.loads(FIXTURE.read_text())


def write_config(path, value):
  path.write_text(json.dumps(value) if not isinstance(value, str) else value)
  # Keep the cached config signature distinct even on filesystems with coarse timestamps.
  stamp = time.time_ns() + 1_000_000_000
  os.utime(path, ns=(stamp, stamp))


def fail_if_called(message: str):
  def reject(*_args: object) -> None:
    raise AssertionError(message)
  return reject


def test_real_x10_response_is_accepted_by_the_new_route_parser():
  document = route_document()
  check_route_document(document, START, END)
  route = NavigationRoute(document['routes'][0])
  assert len(route.points) == 102 and len(route.steps) == 11
  assert route.progress((START[1], START[0]), 2.).instruction['maneuverType'] == 'turn'
  assert distance(route.points[0], (START[1], START[0])) < 1000
  assert distance(route.points[-1], (END[1], END[0])) < 1000


@pytest.mark.parametrize('invalid', ['', 'has space', '\ud800', 'x' * 129, 123])
def test_invalid_explicit_sidecar_token_fails_closed(tmp_path, monkeypatch, invalid):
  config = tmp_path / 'sidecar.json'
  config.write_text(json.dumps({'enabled': True, 'token': invalid}))
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  assert sidecar_token() == ''


@pytest.mark.parametrize('port', [8765, '9000', '\u0663', '\u00b2', '', 'abc', 0, 1, 65535, 65536, -1, True, 1.5, None, [1], '99999999999999999999'])
def test_port_of_is_the_port_the_runtime_reads(tmp_path, monkeypatch, port):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  write_config(config, {'enabled': True, 'port': port})
  assert sidecar_config.sidecar_port() == sidecar_config.port_of({'port': port})
  assert sidecar_config.port_of({}) == sidecar_config.DEFAULT_PORT


def token_of(length):
  """A printable ASCII token of exactly `length` characters that uses both ends of the allowed range."""
  return ('!~' + 'x' * length)[:length]


@pytest.mark.parametrize('length, accepted', [(0, False), (1, False), (9, False), (10, True), (11, True), (24, True),
                                             (127, True), (128, True), (129, False), (1000, False)])
def test_token_strength_boundaries_are_10_and_128(length, accepted):
  token = token_of(length)
  assert len(token) == length
  assert sidecar_config.is_valid_token(token) is accepted
  assert (sidecar_config.token_problem(token) is None) is accepted
  assert sidecar_config.TOKEN_MIN_LENGTH == 10 and sidecar_config.TOKEN_MAX_LENGTH == 128


def test_only_printable_ascii_0x21_to_0x7e_is_accepted_in_a_token():
  codes = [*range(0x300), 0x2028, 0x3042, 0xFF01, 0x1F600, 0xD800, 0xDFFF]
  for code in codes:
    token = 'a' * 9 + chr(code)
    assert sidecar_config.is_valid_token(token) is (0x21 <= code <= 0x7E), hex(code)
  for edge in ('!' * 10, '~' * 10, 'a' * 5 + '!' + 'b' * 4, 'a' * 5 + '~' + 'b' * 4):
    assert sidecar_config.is_valid_token(edge)
  for padded in (' ' + 'a' * 10, 'a' * 10 + ' ', 'a' * 10 + '\n', '\t' + 'a' * 10):
    assert not sidecar_config.is_valid_token(padded)


@pytest.mark.parametrize('value', [None, 0, 1.5, True, b'abcdefghijkl', ['abcdefghijkl'], {'token': 'abcdefghijkl'}])
def test_a_token_must_be_text(value):
  assert sidecar_config.token_problem(value) == 'The token must be text'
  assert not sidecar_config.is_valid_token(value)


def test_token_problem_messages_name_the_rule_but_never_the_token():
  marker = 'ZZSECRETMARKERZZ'
  messages = [sidecar_config.token_problem(marker[:8]) or '', sidecar_config.token_problem(marker + ' x') or '',
              sidecar_config.token_problem(marker * 12) or '', sidecar_config.token_problem(marker + '\u00e9') or '']
  assert '' not in messages and len(set(messages)) == 3  # too short, too long, and two ways to use a bad character
  assert all(marker not in message and marker[:8] not in message for message in messages)
  assert 'at least 10' in (sidecar_config.token_problem('short') or '')
  assert 'at most 128' in (sidecar_config.token_problem('x' * 129) or '')
  assert 'printable ASCII' in (sidecar_config.token_problem('has space 12345') or '')


def test_route_token_reader_stays_lenient_while_the_strength_rule_is_strict(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  for token in ('short', 'a', token_of(9), token_of(128)):
    write_config(config, {'enabled': True, 'token': token})
    assert sidecar_token() == token  # the route server decides what it accepts; this device only sends it
  write_config(config, {'enabled': True})
  assert sidecar_token() == sidecar_config.DEFAULT_TOKEN
  assert not sidecar_config.is_valid_token(sidecar_config.DEFAULT_TOKEN)


@pytest.mark.parametrize('content', [
  'not json', '[]', 'null', '{}', '{"enabled":"true"}', '{"enabled":1}', '{"enabled":false}',
])
def test_sidecar_requires_a_valid_opt_in_config(tmp_path, monkeypatch, content):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  assert not sidecar_config.sidecar_enabled()
  write_config(config, content)
  assert not sidecar_config.sidecar_enabled()


def test_oversized_config_is_ignored_and_valid_changes_are_seen_live(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  write_config(config, {'enabled': True, 'host': '192.168.1.10'})
  assert sidecar_config.sidecar_enabled() and sidecar_config.configured_host() == '192.168.1.10'
  write_config(config, {'enabled': True, 'host': '192.168.1.11'})
  assert sidecar_config.configured_host() == '192.168.1.11'
  config.write_bytes(b' ' * (sidecar_config.MAX_CONFIG_BYTES + 1))
  assert not sidecar_config.sidecar_enabled()
  config.unlink()
  assert not sidecar_config.sidecar_enabled()


@pytest.mark.parametrize('host', ['8.8.8.8', 'example.com', 'http://192.168.1.10', '192.168.1.10:8765',
                                  '0.0.0.0', '::1', 123, []])
def test_unusable_fixed_host_never_falls_back_to_discovery(tmp_path, monkeypatch, host):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  write_config(config, {'enabled': True, 'host': host})
  monkeypatch.setattr(sidecar_config, '_discover', fail_if_called('discovery must not run'))
  assert sidecar_config.resolve_route_server() is None


def test_fixed_host_and_port_need_no_network_probe(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  write_config(config, {'enabled': True, 'host': '192.168.1.10', 'port': '9000'})
  monkeypatch.setattr(sidecar_config, '_discover', fail_if_called('discovery must not run'))
  assert sidecar_config.sidecar_route_server() == RouteServer('192.168.1.10', 9000, SOURCE_FIXED)


def test_auto_discovery_verifies_a_neighbour_then_caches_and_invalidates(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))
  write_config(config, {'enabled': True, 'host': 'auto'})
  monkeypatch.setattr(sidecar_config, 'default_gateway', lambda *_args: '192.168.1.1')
  monkeypatch.setattr(sidecar_config, 'arp_neighbours', lambda *_args: ['192.168.1.22'])
  calls = []
  def probe(host, port):
    calls.append((host, port))
    return host == '192.168.1.22'
  monkeypatch.setattr(sidecar_config, 'probe_status', probe)
  sidecar_config.invalidate_discovery()
  try:
    first = sidecar_config.sidecar_route_server()
    assert first.host == '192.168.1.22' and first.source == 'verified'
    assert len(calls) == 2
    assert sidecar_config.sidecar_route_server().host == first.host and len(calls) == 2
    sidecar_config.invalidate_discovery()
    assert sidecar_config.sidecar_route_server().host == first.host and len(calls) == 4
    write_config(config, {'enabled': True, 'host': 'auto', 'port': 9000})
    assert sidecar_config.sidecar_route_server().port == 9000
    assert ('192.168.1.22', 9000) in calls
  finally:
    sidecar_config.invalidate_discovery()


@pytest.mark.parametrize('body', [
  b'{"service":"openpilot-sidecar","api":true}',
  b'{"service":"openpilot-sidecar","api":"1"}',
  b'{"service":"openpilot-sidecar","api":0}',
  b'{"service":"other","api":1}',
  b'{"service":"openpilot-sidecar","api":1} trailing',
  b'\xff\xfe',
])
def test_discovery_rejects_wrong_or_malformed_status(body):
  assert not sidecar_config.is_sidecar_status(body)


def test_discovery_accepts_only_the_sidecar_status_signature():
  assert sidecar_config.is_sidecar_status(b'{"service":"openpilot-sidecar","api":1}')


@pytest.mark.parametrize('damage', [
  lambda row: row['geometry']['coordinates'].__setitem__(0, [0., 0.]),
  lambda row: row.update(distance=1e8),
  lambda row: row['legs'][0]['steps'][0]['maneuver'].update(instruction='\ud800'),
  lambda row: row['geometry']['coordinates'].extend([[END[1], END[0]]] * 8000),
])
def test_x10_response_rejects_malicious_or_broken_route(damage):
  document = route_document()
  damage(document['routes'][0])
  with pytest.raises(RouteRejected):
    check_route_document(document, START, END)


@pytest.mark.parametrize('damage', [
  lambda row: row['geometry']['coordinates'].__setitem__(1, [float('nan'), START[0]]),
  lambda row: row['geometry']['coordinates'].__setitem__(1, [True, START[0]]),
  lambda row: row['geometry']['coordinates'].__setitem__(1, [181., START[0]]),
  lambda row: row.update(duration=0.),
  lambda row: row.update(distance='1964'),
  lambda row: row['legs'][0]['steps'][0].update(duration=float('inf')),
  lambda row: row['legs'][0]['steps'][1]['maneuver'].update(location=[139., 34.]),
  lambda row: row['legs'][0]['steps'][1]['maneuver'].update(type='x' * 513),
  lambda row: row['legs'][0]['steps'][1]['maneuver'].update(instruction='bad\x00text'),
  lambda row: row['legs'][0]['steps'][0]['bannerInstructions'][0]['primary'].update(text='\ud800'),
  lambda row: row['legs'][0]['steps'][0]['bannerInstructions'][0].update(primary=[]),
  lambda row: row['legs'][0]['steps'][0].update(bannerInstructions=[{}] * 17),
  lambda row: row['legs'][0].update(annotation={'maxspeed': [{'speed': 30, 'unit': 'knots'}] * 101}),
  lambda row: row['legs'][0].update(annotation={'maxspeed': [{'speed': 30, 'unit': 'km/h'}]}),
  lambda row: row.update(legs=[]),
  lambda row: row.update(legs=[row['legs'][0], row['legs'][0]]),
])
def test_real_x10_route_rejects_unsafe_fields_before_new_parser(damage):
  document = route_document()
  damage(document['routes'][0])
  with pytest.raises(RouteRejected):
    check_route_document(document, START, END)


def test_every_alternative_is_checked_before_any_route_is_published():
  document = route_document()
  other = copy.deepcopy(document['routes'][0])
  other['legs'][0]['steps'][0]['maneuver']['instruction'] = '\ud800'
  document['routes'].append(other)
  with pytest.raises(RouteRejected):
    check_route_document(document, START, END)
  document['routes'] = [route_document()['routes'][0]] * 4
  with pytest.raises(RouteRejected):
    check_route_document(document, START, END)


@pytest.mark.parametrize('body', [
  b'', b'\xff\xfe', b'{"code":"Ok",', b'{"distance":NaN}',
  b'{"distance":Infinity}', b'{"distance":1e999}', b'[' * 10000,
])
def test_route_response_rejects_malformed_json_and_nonfinite_numbers(body):
  with pytest.raises(RouteFetchError):
    RouteResponse(200, body).json()


def test_route_fetch_uses_only_the_configured_lan_server(monkeypatch):
  from openpilot.starpilot.navigation import sidecar

  document = route_document()
  calls = []
  class Session:
    def get(self, url, params, timeout):
      calls.append((url, params, timeout))
      return RouteResponse(200, json.dumps(document).encode())

  monkeypatch.setattr(sidecar, 'sidecar_enabled', lambda: True)
  monkeypatch.setattr(sidecar, 'sidecar_route_server', lambda: RouteServer('192.168.1.10', 8765, SOURCE_FIXED))
  engine = SidecarRouteEngine(Session())
  destination = {'name': 'Shibuya', 'latitude': END[0], 'longitude': END[1]}
  route = engine.fetch('shared-secret', (START[1], START[0]), destination)
  assert len(route.steps) == 11
  assert calls[0][0].startswith('http://192.168.1.10:8765/directions/v5/mapbox/driving-traffic/')
  assert 'api.mapbox.com' not in calls[0][0]
  assert calls[0][1]['access_token'] == 'shared-secret'
  assert calls[0][2] == 5.


@pytest.mark.parametrize('failure', ('socket', 'stale_host'))
def test_route_fetch_falls_back_to_ble_and_recovers_lan_after_short_cooldown(monkeypatch, failure):
  from openpilot.starpilot.navigation import sidecar

  body = json.dumps(route_document()).encode()
  calls, ble_calls = [], []
  class Session:
    def get(self, url, params, timeout):
      calls.append(url)
      if len(calls) == 1:
        if failure == 'socket':
          raise RouteFetchError('the LAN socket is unavailable')
        return RouteResponse(404, b'')
      return RouteResponse(200, body)

  def ble_fetch(target):
    ble_calls.append(target)
    return 200, body

  monkeypatch.setattr(sidecar, 'sidecar_enabled', lambda: True)
  monkeypatch.setattr(sidecar, 'sidecar_route_server', lambda: RouteServer('192.168.1.10', 8765, SOURCE_FIXED))
  monkeypatch.setattr(sidecar, 'fetch_ble_route', ble_fetch)
  engine = SidecarRouteEngine(Session())
  destination = {'name': 'Shibuya', 'latitude': END[0], 'longitude': END[1]}
  for _ in range(2):
    assert engine.fetch('shared-secret', (START[1], START[0]), destination).steps
  assert len(calls) == 1  # a reroute while T11 has X10 Wi-Fi does not spend another 5 s on LAN
  assert len(ble_calls) == 2
  assert ble_calls[0].startswith('/directions/v5/mapbox/driving-traffic/')
  assert 'access_token=shared-secret' in ble_calls[0]

  engine._lan_failed_until = 0.0
  assert engine.fetch('shared-secret', (START[1], START[0]), destination).steps
  assert len(calls) == 2  # once the cooldown passes, the normal LAN path resumes
  assert len(ble_calls) == 2


def test_bad_x10_answer_invalidates_discovery_and_never_yields_a_route(monkeypatch):
  from openpilot.starpilot.navigation import sidecar

  document = route_document()
  document['routes'][0]['geometry']['coordinates'][0] = [0., 0.]
  invalidations = []
  monkeypatch.setattr(sidecar, 'sidecar_enabled', lambda: True)
  monkeypatch.setattr(sidecar, 'sidecar_route_server', lambda: RouteServer('192.168.1.10', 8765, SOURCE_FIXED))
  monkeypatch.setattr(sidecar, 'invalidate_discovery', lambda: invalidations.append(True))
  session = SimpleNamespace(get=lambda *_args, **_kwargs: RouteResponse(200, json.dumps(document).encode()))
  with pytest.raises(ValidationError, match='LAN route unavailable'):
    SidecarRouteEngine(session).fetch('secret', (START[1], START[0]),
                                      {'latitude': END[0], 'longitude': END[1]})
  assert invalidations == [True]


def test_route_transport_does_not_follow_redirects_or_read_oversized_answers():
  class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
      if self.path == '/redirect':
        self.send_response(302)
        self.send_header('Location', 'https://api.mapbox.com/')
        self.end_headers()
      else:
        self.send_response(200)
        self.send_header('Content-Length', str(MAX_ROUTE_BYTES + 1))
        self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
      pass

  server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
  thread = threading.Thread(target=server.serve_forever, daemon=True)
  thread.start()
  try:
    base = f'http://127.0.0.1:{server.server_port}'
    assert RouteSession().get(base + '/redirect').status_code == 302
    with pytest.raises(RouteFetchError, match='larger'):
      RouteSession().get(base + '/oversized')
  finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)


@pytest.mark.parametrize('url', [
  'http://8.8.8.8/x', 'https://192.168.1.10/x', 'http://example.com/x',
  'http://localhost/x', 'http://[::1]/x', 'http://192.168.1.10:99999/x',
  'http://0.0.0.0/x', 'http:///x', '/directions/x', '',
])
def test_route_transport_rejects_non_lan_urls_before_connect(monkeypatch, url):
  monkeypatch.setattr(RouteSession, '_exchange', staticmethod(fail_if_called('must not connect')))
  with pytest.raises(RouteFetchError):
    RouteSession().get(url)


def test_route_transport_caps_caller_timeout_and_encodes_token_in_query(monkeypatch):
  from openpilot.starpilot.navigation import sidecar

  calls = []
  def exchange(host, port, target, deadline, limit):
    calls.append((host, port, target, limit))
    return sidecar._Answer(200, '', b'{}', False)
  monkeypatch.setattr(RouteSession, '_exchange', staticmethod(exchange))
  for timeout in (None, 2.5, 60., 0, True):
    RouteSession().get('http://192.168.1.10:8765/a', {'access_token': 'a b&c=d'}, timeout)
  assert [call[3] for call in calls] == [5., 2.5, 5., 5., 5.]
  assert all(call[:3] == ('192.168.1.10', 8765, '/a?access_token=a+b%26c%3Dd') for call in calls)


def test_route_transport_deadline_covers_a_trickling_body(monkeypatch):
  from openpilot.starpilot.navigation import sidecar
  monkeypatch.setattr(sidecar, 'ROUTE_DEADLINE_S', 0.35)
  stop = threading.Event()

  class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
      self.send_response(200)
      self.send_header('Content-Length', '100000')
      self.end_headers()
      try:
        while not stop.is_set():
          self.wfile.write(b' ')
          self.wfile.flush()
          stop.wait(0.04)
      except OSError:
        pass

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
      pass

  server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
  thread = threading.Thread(target=server.serve_forever, daemon=True)
  thread.start()
  try:
    started = time.monotonic()
    with pytest.raises(RouteFetchError, match='timed out'):
      RouteSession().get(f'http://127.0.0.1:{server.server_port}/directions')
    assert 0.25 <= time.monotonic() - started < 1.5
  finally:
    stop.set()
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)


def test_sidecar_runtime_routes_without_a_mapbox_key_and_clears_on_disable(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  config.write_text('{"enabled":true,"host":"192.168.1.10","token":"shared-secret"}')
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))

  class MetricOwner(NavigationOwner):
    def _is_metric(self):
      return True

  owner = MetricOwner(tmp_path / 'navigation', runtime_source=lambda: None)
  saved = owner.configure({'enabled': True}, '0', True)
  owner.select({'name': 'Shibuya', 'latitude': END[0], 'longitude': END[1]}, saved['revision'], True)
  status = owner.snapshot()
  assert status['sidecarEnabled'] is True and status['hasKey'] is False and status['status'] == 'waitingForLocation'

  jobs = []
  class Executor:
    def submit(self, function, *args):
      future = Future()
      jobs.append((function, args, future))
      return future

  runtime = RouteRuntime(owner, engine=SimpleNamespace(fetch=lambda *_args: None), executor=Executor(), sidecar_mode=True)
  position = (9_999_000_000, (START[1], START[0]), 2., 90.)
  assert runtime.update(10_000_000_000, position, 9_000_000_000)['status'] == 'routing'
  assert len(jobs) == 1 and jobs[0][1][0] == 'shared-secret'
  document = route_document()
  jobs[0][2].set_result(NavigationRoute(document['routes'][0]))
  assert runtime.update(11_000_000_000, (10_999_000_000, (START[1], START[0]), 2., 90.), 9_000_000_000)['status'] == 'guiding'

  config.write_text('{"enabled":true,"host":"192.168.1.11","token":"shared-secret"}')
  changed = runtime.update(12_000_000_000, (11_999_000_000, (START[1], START[0]), 2., 90.), 9_000_000_000)
  assert changed['status'] == 'routing' and changed['route'] == [] and not changed['controlValid']
  assert len(jobs) == 2

  config.write_text('{"enabled":false,"host":"192.168.1.11","token":"shared-secret"}')
  stopped = runtime.update(13_000_000_000, (12_999_000_000, (START[1], START[0]), 2., 90.), 9_000_000_000)
  assert stopped['status'] == 'needsKey' and stopped['route'] == [] and not stopped['controlValid']


def test_live_opt_in_switches_route_source_and_drops_inflight_mapbox_result(tmp_path, monkeypatch):
  config = tmp_path / 'sidecar.json'
  monkeypatch.setenv('SIDECAR_CONFIG', str(config))

  class MetricOwner(NavigationOwner):
    def _is_metric(self):
      return True

  owner = MetricOwner(tmp_path / 'navigation', runtime_source=lambda: None)
  saved = owner.configure({'enabled': True, 'token': 'mapbox-test-token'}, '0', True)
  owner.select({'name': 'Shibuya', 'latitude': END[0], 'longitude': END[1]}, saved['revision'], True)

  jobs = []
  class Executor:
    def submit(self, function, *args):
      future = Future()
      jobs.append((function, args, future))
      return future

  runtime = RouteRuntime(owner, executor=Executor())
  assert runtime._auto_mode and not runtime.sidecar_mode
  position = (9_999_000_000, (START[1], START[0]), 2., 90.)
  assert runtime.update(10_000_000_000, position, 9_000_000_000)['status'] == 'routing'
  assert jobs[0][1][0] == 'mapbox-test-token'
  stale_mapbox = jobs[0][2]

  write_config(config, {'enabled': True, 'host': '192.168.1.10', 'token': 'sidecar-test-token'})
  assert sidecar_config.sidecar_enabled()
  assert runtime.update(11_000_000_000, (10_999_000_000, position[1], 2., 90.), 9_000_000_000)['status'] == 'routing'
  assert runtime.sidecar_mode
  assert stale_mapbox.cancelled()
  assert len(jobs) == 2 and jobs[1][1][0] == 'sidecar-test-token'
  assert isinstance(runtime.engine, SidecarRouteEngine)

  write_config(config, {'enabled': False, 'host': '192.168.1.10', 'token': 'sidecar-test-token'})
  assert runtime.update(12_000_000_000, (11_999_000_000, position[1], 2., 90.), 9_000_000_000)['status'] == 'routing'
  assert jobs[1][2].cancelled()
  assert len(jobs) == 3 and jobs[2][1][0] == 'mapbox-test-token'
  assert not isinstance(runtime.engine, SidecarRouteEngine)
