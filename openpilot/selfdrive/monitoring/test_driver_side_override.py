"""Confirmed wheel side must win over an opposing camera estimate."""

from types import SimpleNamespace

import pytest

from openpilot.cereal import log
from opendbc.car.structs import car
from openpilot.selfdrive.monitoring.dmonitoringd import confirmed_wheel_on_right
from openpilot.selfdrive.monitoring.policy import DriverMonitoring


class FakeParams:
  def __init__(self, side: object):
    self.side = side

  def get(self, key: str) -> object:
    return self.side if key == 'DriverSide' else None


@pytest.mark.parametrize(('saved', 'expected'), [
  ('right', True), ('left', False), (None, None), ('auto', None), ('RIGHT', None), (b'right', None),
])
def test_only_confirmed_driver_side_values_override_camera(saved, expected):
  params = FakeParams(saved)
  assert confirmed_wheel_on_right(params) is expected


def driver_state(wheel_on_right_prob: float, *, left_face: bool = True, right_face: bool = True):
  state = log.DriverStateV2.new_message()
  state.wheelOnRightProb = wheel_on_right_prob
  for name, visible in (('leftDriverData', left_face), ('rightDriverData', right_face)):
    data = getattr(state, name)
    data.faceProb = float(visible)
    data.faceOrientation = [0., 0., 0.]
    data.facePosition = [0., 0.]
    data.faceOrientationStd = [0., 0., 0.]
    data.facePositionStd = [0., 0.]
    data.leftEyeProb = data.rightEyeProb = 1.
  return state


def monitoring_inputs(state):
  return {
    'carState': car.CarState(vEgo=20., gearShifter=car.CarState.GearShifter.drive),
    'selfdriveState': SimpleNamespace(enabled=False),
    'modelV2': SimpleNamespace(meta=SimpleNamespace(disengagePredictions=SimpleNamespace(brakeDisengageProbs=[0.]))),
    'extrinsicsCalibration': SimpleNamespace(rpyCalib=[0., 0., 0.]),
    'driverStateV2': state,
  }


@pytest.mark.parametrize(('confirmed_right', 'opposing_probability'), [(True, 0.), (False, 1.)])
def test_confirmed_side_never_drifts_from_opposite_camera_estimate(confirmed_right, opposing_probability):
  monitoring = DriverMonitoring(wheel_side_override=confirmed_right)
  inputs = monitoring_inputs(driver_state(opposing_probability,
                                          left_face=not confirmed_right, right_face=confirmed_right))
  for _ in range(monitoring.settings._WHEELPOS_FILTER_MIN_COUNT + 20):
    monitoring.run_step(inputs)
  packet = monitoring.get_state_packet().driverMonitoringState
  assert packet.isRHD is confirmed_right
  assert packet.visionPolicyState.faceDetected
  assert monitoring.wheelpos_offsetter.filtered_stat.n == 0


def test_live_override_supersedes_calibration_and_removal_resumes_it():
  monitoring = DriverMonitoring(rhd_saved=False)
  inputs = monitoring_inputs(driver_state(1.))
  for _ in range(monitoring.settings._WHEELPOS_FILTER_MIN_COUNT + 20):
    monitoring.run_step(inputs)
  assert monitoring.get_state_packet().driverMonitoringState.isRHD
  learned_count = monitoring.wheelpos_offsetter.filtered_stat.n

  monitoring.wheel_side_override = False
  monitoring.run_step(inputs)
  assert not monitoring.get_state_packet().driverMonitoringState.isRHD
  assert monitoring.wheelpos_offsetter.filtered_stat.n == learned_count

  monitoring.wheel_side_override = None
  monitoring.run_step(inputs)
  assert monitoring.get_state_packet().driverMonitoringState.isRHD
  assert monitoring.wheelpos_offsetter.filtered_stat.n > learned_count
