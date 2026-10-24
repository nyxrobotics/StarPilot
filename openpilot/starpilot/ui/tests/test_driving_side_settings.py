"""Persistent wheel/traffic choices and bounded parked camera proposals."""

import tempfile
import unittest

from openpilot.starpilot.ui.driving_side_settings import DrivingSideSettings, WheelSideProposal


class FakeParams:
  def __init__(self):
    self.values = {}
    self.writes = []

  def get(self, key):
    return self.values.get(key)

  def put(self, key, value, block=False):
    self.writes.append(("put", key, value, block))
    self.values[key] = value

  def remove(self, key):
    self.writes.append(("remove", key))
    self.values.pop(key, None)


class TestDrivingSideSettings(unittest.TestCase):
  def test_typed_detector_fallback_distinguishes_false_from_absent(self):
    from openpilot.common.params import Params

    with tempfile.TemporaryDirectory() as directory:
      params = Params(directory)
      settings = DrivingSideSettings(params, lambda: True)
      self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), (None, None))

      params.put_bool("IsRhdDetected", False, block=True)
      self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), ("left", "right"))

      params.put_bool("IsRhdDetected", True, block=True)
      self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), ("right", "left"))

  def test_saved_wheel_changes_default_road_side_but_never_explicit_road_side(self):
    params = FakeParams()
    params.values["IsRhdDetected"] = True
    settings = DrivingSideSettings(params, lambda: True)

    self.assertTrue(settings.save_wheel_side("left"))
    self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), ("left", "right"))
    self.assertTrue(settings.snapshot().wheel_saved)
    self.assertFalse(settings.snapshot().traffic_saved)

    self.assertTrue(settings.save_traffic_side("left"))
    self.assertTrue(settings.save_wheel_side("right"))
    self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), ("right", "left"))
    self.assertTrue(settings.snapshot().traffic_saved)

    self.assertTrue(settings.clear_wheel_side())
    self.assertEqual((settings.snapshot().wheel_side, settings.snapshot().traffic_side), ("right", "left"))
    self.assertFalse(settings.snapshot().wheel_saved)
    self.assertTrue(settings.clear_traffic_side())
    self.assertFalse(settings.snapshot().traffic_saved)
    self.assertEqual(settings.snapshot().traffic_side, "left")

  def test_writes_require_parked_state_at_confirmation(self):
    params = FakeParams()
    parked = False
    settings = DrivingSideSettings(params, lambda: parked)
    self.assertFalse(settings.save_wheel_side("right"))
    self.assertFalse(settings.save_traffic_side("left"))
    self.assertFalse(settings.clear_wheel_side())
    self.assertFalse(settings.clear_traffic_side())
    self.assertEqual(params.writes, [])

    parked = True
    self.assertTrue(settings.save_wheel_side("right"))
    parked = False
    self.assertFalse(settings.clear_wheel_side())
    self.assertEqual(params.values["DriverSide"], "right")
    self.assertEqual(params.writes, [("put", "DriverSide", "right", True)])


class TestWheelSideProposal(unittest.TestCase):
  def test_only_consecutive_confident_fresh_frames_propose(self):
    proposal = WheelSideProposal()
    for i in range(19):
      self.assertIsNone(proposal.observe(0.95, 0.1, 0.9, i * 0.07))
    self.assertEqual(proposal.observe(0.95, 0.1, 0.9, 19 * 0.07), "right")
    self.assertEqual(proposal.expire(19 * 0.07 + 0.5), "right")
    self.assertIsNone(proposal.expire(19 * 0.07 + 0.76))

  def test_ambiguous_face_side_change_and_stream_gap_reset_proposal(self):
    proposal = WheelSideProposal()
    for i in range(20):
      proposal.observe(0.95, 0.1, 0.9, i * 0.07)
    self.assertEqual(proposal.candidate, "right")

    self.assertIsNone(proposal.observe(0.95, 0.1, 0.2, 1.4))
    self.assertIsNone(proposal.observe(0.05, 0.9, 0.1, 1.47))
    self.assertIsNone(proposal.observe(0.95, 0.1, 0.9, 1.54))
    self.assertIsNone(proposal.observe(0.95, 0.1, 0.9, 2.0))
    self.assertIsNone(proposal.observe(0.95, 0.1, 0.9, 2.0))


if __name__ == "__main__":
  unittest.main()
