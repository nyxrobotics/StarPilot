"""The Android app and the phone bridge must agree on the encrypted BLE wire format."""
import base64
import json
from pathlib import Path
import sys

import pytest

ANDROID = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ANDROID.parents[1] / 'galaxy_bluetooth/bridge'))
import protocol


def test_android_wire_vectors():
  # These fixtures are also consumed by BleWireTest on the JVM.
  root = ANDROID / 'app/src/test/resources/ble'
  key = bytes(range(32))
  for name in ('plain', 'compressed', 'compact'):
    frame = bytes.fromhex((root / f'{name}.hex').read_text())
    expected = json.loads((root / f'{name}.json').read_text())
    assert protocol.open_message(frame[4:], key, 'response') == expected
  frame = bytes.fromhex((root / 'request.hex').read_text())
  assert protocol.open_message(frame[4:], key, 'request') == {'method': 'GET', 'path': '/api/companion/status'}
  with pytest.raises(ValueError):
    protocol.open_message(frame[4:], key, 'response')


def test_raw_body_requests_of_the_android_app_open_in_the_bridge():
  # A request with a raw body (codec 2, stored deflate blocks, no base64) as BleWire.sealRaw writes it; BleWireRawTest checks that the app still makes exactly these frames.
  data = json.loads((ANDROID / 'app/src/test/resources/ble/request-raw.json').read_text())
  key = bytes.fromhex(data['key_hex'])
  assert len(data['cases']) >= 3
  for case in data['cases']:
    frame = bytes.fromhex(case['frame_hex'])
    assert int.from_bytes(frame[:4], 'big') == len(frame) - 4
    opened = protocol.open_message(frame[4:], key, 'request')
    body = base64.b64decode(opened.pop('body'))
    assert body == bytes.fromhex(case['body_hex']), case['name']
    assert opened == json.loads(case['meta']), case['name']
    with pytest.raises(ValueError):
      protocol.open_message(frame[4:], key, 'response')
