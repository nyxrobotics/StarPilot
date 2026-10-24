"""The settings screen's X10 status check: bounded, credential-free, classified, and it never raises."""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import threading
import time
from types import SimpleNamespace

import pytest

from openpilot.starpilot.navigation import sidecar_config, sidecar_status
from openpilot.starpilot.navigation.sidecar_status import (
  ANSWERED, HEADLINES, Verdict, X10Status, check_x10, classify_status, fetch_x10_status, format_uptime, is_own_address,
)

LOOPBACK = '127.0.0.1'


def status_document(**changes):
  document = {'service': 'openpilot-sidecar', 'api': 1, 'version': '1.4.2', 'ready': True,
              'engine': {'state': 'ready', 'tiles': 'valhalla_tiles.tar', 'tilesBytes': 3_620_000_000}, 'uptimeSec': 7200}
  document.update(changes)
  return document


def answer_with(status=200, body=b'', headers=None):
  headers = {'Content-Type': 'application/json', **(headers or {})}

  def answer(handler):
    handler.send_response(status)
    for key, value in headers.items():
      handler.send_header(key, value)
    handler.send_header('Content-Length', str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)

  return answer


def answer_json(document, status=200):
  return answer_with(status, json.dumps(document).encode())


@contextmanager
def serve(answer):
  """A stand-in route server on 127.0.0.1 that answers every GET with `answer(handler)` and records what it was sent."""
  requests = []

  class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002  # stdlib override
      pass

    def do_GET(self):
      requests.append((self.command, self.path, {key.lower(): value for key, value in self.headers.items()}))
      answer(self)

  server = ThreadingHTTPServer((LOOPBACK, 0), Handler)
  thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
  thread.start()
  try:
    yield server.server_address[1], requests
  finally:
    server.shutdown()
    thread.join(timeout=2)
    server.server_close()


@contextmanager
def raw_server(behavior):
  """A TCP server that runs behavior(connection) for each client, for peers that do not speak HTTP properly."""
  listener = socket.socket()
  listener.bind((LOOPBACK, 0))
  listener.listen(8)
  stop = threading.Event()

  def loop():
    listener.settimeout(0.05)
    while not stop.is_set():
      try:
        connection, _ = listener.accept()
      except OSError:
        continue
      threading.Thread(target=run, args=(connection,), daemon=True).start()

  def run(connection):
    try:
      behavior(connection)
    except OSError:
      pass
    finally:
      connection.close()

  thread = threading.Thread(target=loop, daemon=True)
  thread.start()
  try:
    yield listener.getsockname()[1]
  finally:
    stop.set()
    thread.join(timeout=2)
    listener.close()


# ---------------------------------------------------------------- what the X10 says

def test_a_ready_route_server_is_reported_with_its_facts():
  with serve(answer_json(status_document())) as (port, requests):
    status = fetch_x10_status(LOOPBACK, port)
  assert status.verdict is Verdict.READY and status.ready and status.answered
  assert (status.host, status.port, status.api, status.version, status.engine, status.uptime_s, status.http_status) == \
         (LOOPBACK, port, 1, '1.4.2', 'ready', 7200, 200)
  assert status.headline == 'X10 ready' and status.short == 'ready' and status.detail == 'app 1.4.2, up 2 h'
  assert 0 <= status.elapsed_s < 1.0
  assert len(requests) == 1


@pytest.mark.parametrize('changes, verdict, detail', [
  ({}, Verdict.READY, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': {'state': 'warming'}}, Verdict.WARMING, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': {'state': 'noTiles'}}, Verdict.NO_MAP_DATA, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': {'state': 'error'}}, Verdict.ENGINE_ERROR, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': {'state': 'ready'}}, Verdict.NOT_READY, 'app 1.4.2, test route failed, up 2 h'),
  ({'ready': False, 'engine': {'state': 'rebooting'}}, Verdict.NOT_READY, 'app 1.4.2, engine rebooting, up 2 h'),
  ({'ready': False, 'engine': {'state': 'x' * 40}}, Verdict.NOT_READY, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': {'state': 'a b'}}, Verdict.NOT_READY, 'app 1.4.2, up 2 h'),
  ({'ready': False, 'engine': None}, Verdict.NOT_READY, 'app 1.4.2, up 2 h'),
  ({'ready': False}, Verdict.NOT_READY, 'app 1.4.2, test route failed, up 2 h'),
  ({'ready': 'yes'}, Verdict.NOT_READY, 'app 1.4.2, test route failed, up 2 h'),
  ({'ready': None, 'engine': {'state': 'ready'}}, Verdict.READY, 'app 1.4.2, up 2 h'),
  ({'ready': None, 'engine': {'state': 'warming'}}, Verdict.WARMING, 'app 1.4.2, up 2 h'),
  ({'ready': True, 'engine': {'state': 'error'}}, Verdict.READY, 'app 1.4.2, up 2 h'),
  ({'api': 2}, Verdict.READY, 'api 2, app 1.4.2, up 2 h'),
])
def test_engine_state_decides_the_verdict(changes, verdict, detail):
  document = status_document(**changes)
  if changes.get('ready', 0) is None:
    del document['ready']
  status = classify_status(LOOPBACK, 8765, 200, json.dumps(document).encode())
  assert (status.verdict, status.detail) == (verdict, detail)
  assert status.headline == HEADLINES[verdict] and status.answered is (verdict in ANSWERED)


CASES = [
  ('404', 404, b'not found', 'HTTP 404'),
  ('500', 500, b'', 'HTTP 500'),
  ('403', 403, b'{"error":"forbidden"}', 'HTTP 403: access refused'),
  ('301', 301, b'', 'HTTP 301'),
  ('html', 200, b'<html>router login</html>', 'answer is not JSON'),
  ('empty', 200, b'', 'answer is not JSON'),
  ('not-utf8', 200, b'\xff\xfe', 'answer is not JSON'),
  ('list', 200, b'[]', 'not an openpilot-sidecar'),
  ('string', 200, b'"openpilot-sidecar"', 'not an openpilot-sidecar'),
  ('other-service', 200, b'{"service":"something-else","api":1}', 'not an openpilot-sidecar'),
  ('api-0', 200, json.dumps(status_document(api=0)).encode(), 'unknown API version'),
  ('api-text', 200, json.dumps(status_document(api='1')).encode(), 'unknown API version'),
  ('api-bool', 200, json.dumps(status_document(api=True)).encode(), 'unknown API version'),
  ('api-null', 200, json.dumps(status_document(api=None)).encode(), 'unknown API version'),
  ('too-large', 200, b'x' * (sidecar_config.MAX_STATUS_BYTES + 1), 'answer is too large'),
]


@pytest.mark.parametrize('http_status, body, detail', [pytest.param(*case[1:], id=case[0]) for case in CASES])
def test_anything_that_is_not_the_route_server_is_named_as_such(http_status, body, detail):
  status = classify_status(LOOPBACK, 8765, http_status, body)
  assert (status.verdict, status.detail, status.http_status) == (Verdict.WRONG_SERVICE, detail, http_status)
  assert not status.answered and not status.ready and status.api is None


def test_wrong_answers_over_a_real_socket():
  cases = [answer_with(404, b'nope'), answer_with(200, b'<html></html>', {'Content-Type': 'text/html'}),
           answer_json({'service': 'other', 'api': 1}), answer_with(200, b'x' * 20_000)]
  for answer in cases:
    with serve(answer) as (port, requests):
      status = fetch_x10_status(LOOPBACK, port)
    assert status.verdict is Verdict.WRONG_SERVICE and len(requests) == 1, status


def test_a_redirect_is_reported_and_never_followed():
  with serve(answer_json(status_document())) as (other_port, other_requests):
    redirect = answer_with(302, b'', {'Location': f'http://{LOOPBACK}:{other_port}/status'})
    with serve(redirect) as (port, requests):
      status = fetch_x10_status(LOOPBACK, port)
  assert status.verdict is Verdict.WRONG_SERVICE and status.http_status == 302
  assert len(requests) == 1 and other_requests == []


def answer_garbage(connection):
  connection.recv(4096)
  connection.sendall(b'garbage that is not HTTP\r\n\r\n')


def greet_like_ssh(connection):
  connection.sendall(b'SSH-2.0-OpenSSH_9.6\r\n')
  connection.recv(4096)


def close_after_the_request(connection):
  connection.recv(4096)  # a clean close without any answer


@pytest.mark.parametrize('behavior', [answer_garbage, greet_like_ssh, close_after_the_request])
def test_a_peer_that_does_not_speak_http_is_the_wrong_service(behavior):
  with raw_server(behavior) as port:
    status = fetch_x10_status(LOOPBACK, port)
  assert status.verdict is Verdict.WRONG_SERVICE and status.detail == 'not an HTTP server'


# ---------------------------------------------------------------- nothing answers, or too slowly

def test_a_closed_port_is_unreachable():
  with socket.socket() as probe:
    probe.bind((LOOPBACK, 0))
    port = probe.getsockname()[1]
  status = fetch_x10_status(LOOPBACK, port)
  assert status.verdict is Verdict.UNREACHABLE and 'refused' in status.detail and not status.answered
  assert status.headline == 'X10 not reachable'


def test_a_peer_that_accepts_and_stays_silent_is_cut_off_in_time():
  with socket.socket() as listener:  # the kernel completes the handshake; nobody ever reads or answers
    listener.bind((LOOPBACK, 0))
    listener.listen(1)
    started = time.monotonic()
    status = fetch_x10_status(LOOPBACK, listener.getsockname()[1])
    elapsed = time.monotonic() - started
  assert status.verdict is Verdict.UNREACHABLE and status.detail == 'no answer in time'
  assert 0.5 <= elapsed < 1.5 and status.elapsed_s < 1.5


def test_a_peer_that_trickles_bytes_cannot_stretch_the_check():
  line = b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\n\r\n' + b'{' * 400

  def trickle(connection):
    connection.recv(4096)
    for index in range(len(line)):
      connection.sendall(line[index:index + 1])
      time.sleep(0.25)  # every byte is on time for the 0.7 s socket limit, the total is not

  with raw_server(trickle) as port:
    started = time.monotonic()
    status = fetch_x10_status(LOOPBACK, port)
    elapsed = time.monotonic() - started
  assert status.verdict is Verdict.UNREACHABLE and status.detail == 'no answer in time'
  assert elapsed < sidecar_config.PROBE_DEADLINE_S + 0.4


def test_a_peer_that_never_ends_its_body_is_cut_off_at_the_size_limit_or_the_deadline():
  def endless(connection):
    connection.recv(4096)
    connection.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n')
    while True:
      connection.sendall(b'{"a":' * 200)

  with raw_server(endless) as port:
    started = time.monotonic()
    status = fetch_x10_status(LOOPBACK, port)
    elapsed = time.monotonic() - started
  assert status.verdict is Verdict.WRONG_SERVICE and status.detail == 'answer is too large' and elapsed < 1.5


@pytest.mark.parametrize('host, port', [(LOOPBACK, 0), (LOOPBACK, 70000), (LOOPBACK, -1), ('192.168.1.20', 'text'), (LOOPBACK, None)])
def test_nonsense_ports_are_a_verdict_never_an_exception(host, port):
  status = fetch_x10_status(host, port)
  assert status.verdict is Verdict.UNREACHABLE and status.headline == 'X10 not reachable'


def test_no_route_is_named(monkeypatch):
  def unreachable(self, address):
    raise OSError(101, 'Network is unreachable')

  monkeypatch.setattr(sidecar_config.DeadlineSocket, 'connect', unreachable)
  status = fetch_x10_status('10.1.2.3', 8765)
  assert status.verdict is Verdict.UNREACHABLE and status.detail == 'no route to that address'


# ---------------------------------------------------------------- what is (not) sent, and what is shown

def test_only_a_plain_get_without_any_credential_is_sent():
  with serve(answer_json(status_document())) as (port, requests):
    fetch_x10_status(LOOPBACK, port)
  [(method, path, headers)] = requests
  assert (method, path) == ('GET', '/status')  # no query string: no token, no position, nothing else
  assert set(headers) == {'host', 'accept', 'accept-encoding', 'connection', 'user-agent'}
  assert headers['host'] == f'{LOOPBACK}:{port}' and headers['connection'] == 'close' and headers['accept-encoding'] == 'identity'
  assert 'authorization' not in headers and 'cookie' not in headers and 'token' not in json.dumps(headers).lower()


def test_text_from_the_peer_is_shown_only_from_a_fixed_alphabet():
  hostile = status_document(version='<script>alert(1)</script>', uptimeSec=-5, engine={'state': 'rm -rf /'})
  hostile['ready'] = False
  status = classify_status(LOOPBACK, 8765, 200, json.dumps(hostile).encode())
  assert status.version == '' and status.engine == '' and status.uptime_s is None
  assert status.detail == '' and 'script' not in status.detail

  for version, shown in (('1.4.2-rc1+build.7', '1.4.2-rc1+build.7'), ('v' + '1' * 24, ''), ('', ''), (None, ''), (14, ''),
                         ('1.4 2', ''), ('1.4\n2', ''), ('café', ''), ('日本', ''), ('1' * 24, '1' * 24)):
    result = classify_status(LOOPBACK, 8765, 200, json.dumps(status_document(version=version)).encode())
    assert result.version == shown, version
  for uptime, expected in ((0, 0), (59, 59), (True, None), (1.5, None), ('7', None), (None, None), (10**12, None), (-1, None)):
    result = classify_status(LOOPBACK, 8765, 200, json.dumps(status_document(uptimeSec=uptime)).encode())
    assert result.uptime_s == expected, uptime


@pytest.mark.parametrize('seconds, text', [(0, 'up 0 s'), (59, 'up 59 s'), (89, 'up 89 s'), (90, 'up 2 min'), (3600, 'up 60 min'),
                                          (5399, 'up 90 min'), (5400, 'up 2 h'), (7200, 'up 2 h'), (47 * 3600, 'up 47 h'),
                                          (48 * 3600, 'up 2 d'), (10 * 86400, 'up 10 d')])
def test_uptime_is_shown_in_one_short_unit(seconds, text):
  assert format_uptime(seconds) == text


def test_every_verdict_has_a_headline_and_the_status_tuple_is_consistent():
  assert set(HEADLINES) == set(Verdict)
  for verdict in Verdict:
    status = X10Status(verdict, LOOPBACK, 8765)
    assert status.headline == HEADLINES[verdict] and status.answered is (verdict in ANSWERED) and status.ready is (verdict is Verdict.READY)


# ---------------------------------------------------------------- the comma's own address

def test_the_comma_own_address_is_caught_before_anything_is_sent():
  with serve(answer_json(status_document())) as (port, requests):
    status = check_x10(LOOPBACK, port)
  assert status.verdict is Verdict.OWN_ADDRESS and requests == []
  assert status.headline == "Comma's own address" and "X10's address" in status.detail


def test_another_address_is_probed(monkeypatch):
  monkeypatch.setattr(sidecar_status, 'is_own_address', lambda host: False)
  with serve(answer_json(status_document())) as (port, requests):
    status = check_x10(LOOPBACK, port)
  assert status.verdict is Verdict.READY and len(requests) == 1


def test_own_address_detection_uses_the_source_address_without_sending(monkeypatch):
  assert is_own_address('127.0.0.1') and is_own_address('127.1.2.3')
  assert not is_own_address('') and not is_own_address('x10.local') and not is_own_address('300.1.1.1')
  events = []

  class FakeUdp:
    def __init__(self, source):
      self.source = source

    def __enter__(self):
      return self

    def __exit__(self, *_args):
      return False

    def connect(self, address):
      events.append(('connect', address))

    def getsockname(self):
      return (self.source, 40000)

    def send(self, *_args):
      raise AssertionError('nothing may be sent')

    sendto = send

  def fake_socket_module(factory):
    return SimpleNamespace(socket=factory, AF_INET=socket.AF_INET, SOCK_DGRAM=socket.SOCK_DGRAM)

  monkeypatch.setattr(sidecar_status, 'socket', fake_socket_module(lambda *_args: FakeUdp('192.168.1.5')))
  assert is_own_address('192.168.1.5') and not is_own_address('192.168.1.6')
  assert events == [('connect', ('192.168.1.5', 9)), ('connect', ('192.168.1.6', 9))]

  def no_route(*_args):
    raise OSError(101, 'Network is unreachable')

  monkeypatch.setattr(sidecar_status, 'socket', fake_socket_module(no_route))
  assert not is_own_address('10.9.9.9')
