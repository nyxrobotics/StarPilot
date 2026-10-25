"""Exercise the local route socket and encrypted-bridge request surface together."""
import base64
import gzip
import json
from pathlib import Path
import random
import tempfile
import threading
import time

from openpilot.starpilot.galaxy_bluetooth.bridge.ble_route import RouteBrokerServer, fetch_route
from openpilot.starpilot.galaxy_bluetooth.bridge.proxy import GalaxyProxy


TARGET = ('/directions/v5/mapbox/driving-traffic/139.1,35.1;139.2,35.2'
          '?access_token=example-secret-token&steps=true')


def _reply(proxy, path, method='GET', body=b''):
  result = proxy.handle({'id': 'test', 'path': path, 'method': method, 'body': base64.b64encode(body).decode()})
  return result['status'], json.loads(base64.b64decode(result['body'])) if result['body'] else None


def test_route_socket_and_gatt_chunk_round_trip():
  with tempfile.TemporaryDirectory() as directory:
    server = RouteBrokerServer(str(Path(directory) / 'route.sock'))
    proxy = GalaxyProxy(route_broker=server.start())
    result = []
    worker = threading.Thread(target=lambda: result.append(fetch_route(TARGET, server.path)), daemon=True)
    try:
      worker.start()
      for _ in range(100):
        status, job = _reply(proxy, '/_bridge/sidecar/poll')
        if status == 200:
          break
        time.sleep(.01)
      assert job['target'] == TARGET
      assert job['next_sequence'] == 0
      route = json.dumps({'code': 'Ok', 'routes': [{'steps': ['turn'] * 5000}]}).encode()
      packed = gzip.compress(route)
      size = max(1, len(packed) // 3)
      chunks = [packed[index:index + size] for index in range(0, len(packed), size)]
      for sequence, chunk in enumerate(chunks):
        path = f'/_bridge/sidecar/chunk/{job["id"]}/{sequence}?last={int(sequence == len(chunks) - 1)}&status=200&encoding=gzip'
        assert _reply(proxy, path, 'POST', chunk) == (200, {'next': sequence + 1})
        if sequence != len(chunks) - 1:
          assert _reply(proxy, '/_bridge/sidecar/poll')[1]['next_sequence'] == sequence + 1
      worker.join(timeout=2)
      assert result == [(200, route)]
      assert _reply(proxy, '/_bridge/sidecar/poll') == (204, None)
    finally:
      if job is not None and worker.is_alive():
        _reply(proxy, f'/_bridge/sidecar/fail/{job["id"]}', 'POST')
      server.close()


def test_route_poll_cursor_resumes_after_lost_chunk_ack():
  with tempfile.TemporaryDirectory() as directory:
    server = RouteBrokerServer(str(Path(directory) / 'route.sock'))
    proxy = GalaxyProxy(route_broker=server.start())
    result = []
    worker = threading.Thread(target=lambda: result.append(fetch_route(TARGET, server.path)), daemon=True)
    job = None
    try:
      worker.start()
      for _ in range(100):
        status, job = _reply(proxy, '/_bridge/sidecar/poll')
        if status == 200:
          break
        time.sleep(.01)
      assert status == 200 and job['next_sequence'] == 0
      route = random.Random(7).randbytes(5000)
      packed = gzip.compress(route)
      chunks = [packed[index:index + 2048] for index in range(0, len(packed), 2048)]
      assert len(chunks) >= 3
      first = f'/_bridge/sidecar/chunk/{job["id"]}/0?last=0&status=200&encoding=gzip'
      _reply(proxy, first, 'POST', chunks[0])  # The sender never sees this acknowledgement.
      assert _reply(proxy, '/_bridge/sidecar/poll')[1]['next_sequence'] == 1
      assert _reply(proxy, first, 'POST', chunks[0]) == (200, {'next': 1})
      for sequence in range(1, len(chunks)):
        path = f'/_bridge/sidecar/chunk/{job["id"]}/{sequence}?last={int(sequence == len(chunks) - 1)}&status=200&encoding=gzip'
        assert _reply(proxy, path, 'POST', chunks[sequence]) == (200, {'next': sequence + 1})
      worker.join(timeout=2)
      assert result == [(200, route)]
    finally:
      if job is not None and worker.is_alive():
        _reply(proxy, f'/_bridge/sidecar/fail/{job["id"]}', 'POST')
      server.close()


def test_route_chunks_reject_out_of_order_and_invalid_gzip():
  with tempfile.TemporaryDirectory() as directory:
    server = RouteBrokerServer(str(Path(directory) / 'route.sock'))
    proxy = GalaxyProxy(route_broker=server.start())
    result = []
    def run():
      try:
        result.append(fetch_route(TARGET, server.path))
      except OSError:
        result.append('unavailable')
    worker = threading.Thread(target=run, daemon=True)
    try:
      worker.start()
      for _ in range(100):
        status, job = _reply(proxy, '/_bridge/sidecar/poll')
        if status == 200:
          break
        time.sleep(.01)
      assert job is not None
      prefix = f'/_bridge/sidecar/chunk/{job["id"]}'
      assert _reply(proxy, prefix + '/1?last=0&status=200&encoding=gzip', 'POST', b'x')[0] == 400
      assert _reply(proxy, prefix + '/0?last=1&status=200&encoding=gzip', 'POST', b'not gzip')[0] == 400
      worker.join(timeout=2)
      assert result == ['unavailable']
    finally:
      if job is not None and worker.is_alive():
        _reply(proxy, f'/_bridge/sidecar/fail/{job["id"]}', 'POST')
      server.close()


def test_sidecar_navigation_forwards_bearer_without_galaxy_cookie():
  proxy = GalaxyProxy()
  calls = []
  proxy.authenticate = lambda: (_ for _ in ()).throw(AssertionError('Galaxy cookie authentication must not run'))
  proxy.send = lambda target, method, body, headers: (calls.append((target, method, headers.copy())) or
                                                      (200, {'content-type': 'application/json'}, b'{}', ''))
  request = {'id': 'nav', 'path': '/api/navigation', 'method': 'POST',
             'body': base64.b64encode(b'{}').decode(), 'headers': {'Authorization': 'Bearer valid-long-secret'}}
  assert proxy.handle(request)['status'] == 200
  assert calls[0][0:2] == ('/api/navigation', 'POST')
  assert calls[0][2]['Authorization'] == 'Bearer valid-long-secret'
  assert calls[0][2]['X-StarPilot-Ble-Bridge'] == '1'
  assert 'Cookie' not in calls[0][2] and 'Origin' not in calls[0][2]
  request['path'] = '/api/navigation/status'
  assert proxy.handle(request)['status'] == 502  # other Galaxy paths still require the browser session
