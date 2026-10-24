"""The X10 bridge has its own narrow authentication; Galaxy sessions are unchanged."""

import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from openpilot.starpilot.galaxy.access import GalaxyAccessOwner
from openpilot.starpilot.galaxy.server import make_remote_server, make_server
from openpilot.starpilot.navigation import sidecar_config
from openpilot.starpilot.navigation.owner import NavigationOwner, ValidationError


TOKEN = 'X10-route-link-example-key-2026'
PLACE = {'name': 'Library', 'latitude': 41.1, 'longitude': -88.2}


class SidecarNavigationHttpTest(unittest.TestCase):
  def setUp(self):
    temporary = tempfile.TemporaryDirectory()
    self.addCleanup(temporary.cleanup)
    root = Path(temporary.name)
    self.config_path = root / 'sidecar.json'
    environment = patch.dict(os.environ, {sidecar_config.CONFIG_ENV: str(self.config_path)})
    environment.start()
    self.addCleanup(environment.stop)
    self.config(enabled=True, host='127.0.0.1', token=TOKEN)
    self.navigation = NavigationOwner(root / 'navigation', runtime_source=lambda: None, transient_root=root / 'boot-navigation')
    self.server = make_server(port=0, owner=GalaxyAccessOwner(root / 'access'), navigation=self.navigation, parked=lambda: True)
    self.remote = make_remote_server(self.server, port=0)
    self.threads = []
    for server in (self.server, self.remote):
      thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
      thread.start()
      self.threads.append((server, thread))
    self.addCleanup(self.close)

  def close(self):
    for server, thread in reversed(self.threads):
      server.shutdown()
      thread.join(timeout=2)
      server.server_close()

  def config(self, **values):
    self.config_path.write_text(json.dumps(values), encoding='utf-8')
    sidecar_config._cache = (None, {})

  def request(self, method='GET', *, payload=None, token=TOKEN, origin=None, host=None, remote=False):
    server = self.remote if remote else self.server
    headers = {'Host': host or f'127.0.0.1:{server.server_port}'}
    if token is not None:
      headers['Authorization'] = 'Bearer ' + token
    if origin is not None:
      headers['Origin'] = origin
    if payload is not None:
      headers['Content-Type'] = 'application/json'
    connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=2)
    try:
      body = json.dumps(payload) if payload is not None else None
      connection.request(method, '/api/navigation', body, headers)
      response = connection.getresponse()
      return response.status, json.loads(response.read())
    finally:
      connection.close()

  def test_requires_fixed_x10_host_and_strong_explicit_token(self):
    self.assertEqual(self.request(token=None)[0], 401)
    status, denied = self.request(token='wrong-token-which-is-long-enough')
    self.assertEqual(status, 401)
    self.assertNotIn(TOKEN, json.dumps(denied))
    self.config(enabled=True, host='auto', token=TOKEN)
    self.assertEqual(self.request()[0], 403)
    self.config(enabled=True, host='192.168.1.50', token=TOKEN)
    self.assertEqual(self.request()[0], 403)  # the peer is 127.0.0.1, not the configured X10
    self.config(enabled=True, host='127.0.0.1', token='short')
    self.assertEqual(self.request(token='short')[0], 403)
    self.config(enabled=False, host='127.0.0.1', token=TOKEN)
    self.assertEqual(self.request()[0], 403)

  def test_bridge_accepts_tokens_of_10_to_128_printable_characters_only(self):
    def token_of(length):
      return ('!~' + 'x' * length)[:length]

    for length, expected in ((0, 403), (1, 403), (9, 403), (10, 200), (11, 200), (24, 200), (128, 200), (129, 403)):
      token = token_of(length)
      self.config(enabled=True, host='127.0.0.1', token=token)
      status = self.request(token=token or None)[0]
      self.assertEqual(status, expected, length)
    # The configured token itself is refused before any header is compared, so a well-formed header proves the config check.
    for bad in ('has space in token', ' leading-space-token', 'trailing-space-token ', 'tab\tinside-token', 'new\nline-token-0',
                'nul\x00-token-0123', 'del\x7f-token-0123', 'caf\u00e9-token-0123', '\u65e5\u672c\u8a9e\u306e\u30c8\u30fc\u30af\u30f3\u3067\u3059\u3088'):
      self.config(enabled=True, host='127.0.0.1', token=bad)
      self.assertEqual(self.request(token='unrelated-but-valid-token')[0], 403, repr(bad))
    for wrong_type in (None, 12345678901234567890, True, ['abcdefghijklmnop']):
      self.config(enabled=True, host='127.0.0.1', token=wrong_type)
      self.assertEqual(self.request(token='unrelated-but-valid-token')[0], 403, repr(wrong_type))

  def test_bridge_rejects_remote_tunnel_browser_origin_and_forged_host(self):
    self.assertEqual(self.request(remote=True)[0], 403)
    self.assertEqual(self.request(origin='http://127.0.0.1')[0], 403)
    self.assertEqual(self.request(host=f'evil.example:{self.server.server_port}')[0], 403)
    connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=2)
    try:
      connection.request('GET', '/api/navigation/status', headers={
        'Host': f'127.0.0.1:{self.server.server_port}', 'Authorization': 'Bearer ' + TOKEN,
      })
      response = connection.getresponse()
      self.assertEqual(response.status, 401)  # X10's token is not a Galaxy browser session
      response.read()
    finally:
      connection.close()
    self.assertIsNone(self.navigation.read()['destination'])

  def test_minimal_state_and_destination_round_trip_without_mapbox_key(self):
    status, initial = self.request()
    self.assertEqual(status, 200)
    self.assertEqual(initial['destination'], '')
    self.assertEqual(initial['lastPosition'], {'latitude': '', 'longitude': ''})
    self.assertIs(type(initial['isMetric']), bool)
    self.assertEqual(initial['navigationStatus'], 'disabled')
    self.assertIsNone(initial['instruction'])
    self.assertEqual(self.request('POST', payload=PLACE), (200, {'message': 'Destination set'}))
    document = self.navigation.read()
    self.assertTrue(document['enabled'])
    self.assertEqual(document['token'], '')
    self.assertEqual(document['destination']['name'], PLACE['name'])
    status, state = self.request()
    self.assertEqual(status, 200)
    self.assertEqual(json.loads(state['destination'])['name'], PLACE['name'])
    self.assertEqual(state['navigationStatus'], 'waitingForLocation')
    self.assertIsNone(state['instruction'])
    self.assertNotIn(TOKEN, json.dumps(state))
    self.assertNotIn('token', state)
    self.assertEqual(self.request('DELETE'), (200, {'message': 'Destination cleared'}))
    self.assertEqual(self.request()[1]['destination'], '')
    self.assertIsNone(self.request()[1]['instruction'])

  def test_current_maneuver_is_narrow_and_clears_with_stale_runtime(self):
    self.assertEqual(self.request('POST', payload=PLACE)[0], 200)
    revision = self.navigation.read()['revision']
    maneuver = {'text': 'Turn left onto Main Street', 'maneuverType': 'turn', 'maneuverModifier': 'left',
                'distanceMeters': 42.0, 'remainingDistanceMeters': 1500.0, 'remainingDurationSeconds': 180.0,
                'privateRouteData': 'must not escape'}
    self.navigation.runtime_source = lambda: {'revision': revision, 'status': 'guiding', 'instruction': maneuver, 'route': []}
    status, state = self.request()
    self.assertEqual(status, 200)
    self.assertEqual(state['navigationStatus'], 'guiding')
    self.assertEqual(state['instruction'], {key: value for key, value in maneuver.items() if key != 'privateRouteData'})
    self.assertNotIn('privateRouteData', json.dumps(state))

    # An absent runtime sample is how NavigationStatusSource reports expired
    # cereal messages; never keep the previous maneuver on the X10.
    self.navigation.runtime_source = lambda: None
    status, state = self.request()
    self.assertEqual(status, 200)
    self.assertEqual(state['navigationStatus'], 'waitingForLocation')
    self.assertIsNone(state['instruction'])

    self.navigation.runtime_source = lambda: {'revision': revision, 'status': 'guiding',
                                               'instruction': dict(maneuver, distanceMeters=float('nan')), 'route': []}
    self.assertIsNone(self.request()[1]['instruction'])

  def test_invalid_destination_and_revoked_token_do_not_write(self):
    before = self.navigation.read()
    for bad in ({'name': '', 'latitude': 41.1, 'longitude': -88.2},
                {'name': 'Bad', 'latitude': 100, 'longitude': -88.2},
                {'name': 'Bad', 'latitude': 41.1, 'longitude': -88.2, 'extra': True}):
      self.assertEqual(self.request('POST', payload=bad)[0], 400)
    self.assertEqual(self.navigation.read(), before)
    self.config(enabled=True, host='127.0.0.1', token='different-long-example-key')
    self.assertEqual(self.request('POST', payload=PLACE)[0], 401)
    self.assertEqual(self.navigation.read(), before)

  def test_failed_destination_selection_does_not_leave_navigation_enabled(self):
    before = self.navigation.read()
    with patch.object(NavigationOwner, '_navigate', side_effect=ValidationError('Selection failed')):
      self.assertEqual(self.request('POST', payload=PLACE)[0], 400)
    self.assertEqual(self.navigation.read(), before)

  def test_status_never_discloses_navigation_or_sidecar_tokens(self):
    mapbox_token = 'pk.private-mapbox-token'
    self.navigation.configure({'token': mapbox_token}, self.navigation.read()['revision'], lambda: True)
    status, state = self.request()
    self.assertEqual(status, 200)
    self.assertEqual(set(state), {'destination', 'lastPosition', 'isMetric', 'navigationStatus', 'instruction'})
    self.assertNotIn(mapbox_token, json.dumps(state))
    self.assertNotIn(TOKEN, json.dumps(state))

  def test_ambiguous_framing_and_read_bodies_are_rejected(self):
    body = json.dumps(PLACE).encode()
    connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=2)
    try:
      connection.putrequest('POST', '/api/navigation', skip_host=True)
      connection.putheader('Host', f'127.0.0.1:{self.server.server_port}')
      connection.putheader('Authorization', 'Bearer ' + TOKEN)
      connection.putheader('Content-Type', 'application/json')
      connection.putheader('Content-Length', str(len(body)))
      connection.putheader('Content-Length', str(len(body)))
      connection.endheaders(body)
      response = connection.getresponse()
      self.assertEqual(response.status, 413)
      response.read()
    finally:
      connection.close()
    connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=2)
    try:
      connection.request('GET', '/api/navigation', b'extra', headers={
        'Host': f'127.0.0.1:{self.server.server_port}', 'Authorization': 'Bearer ' + TOKEN,
      })
      response = connection.getresponse()
      self.assertEqual(response.status, 400)
      response.read()
    finally:
      connection.close()
    self.assertIsNone(self.navigation.read()['destination'])


if __name__ == '__main__':
  unittest.main()
