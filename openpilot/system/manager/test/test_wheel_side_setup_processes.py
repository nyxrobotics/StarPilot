"""Wheel-side setup needs cabin inference without driver-monitoring demo or audio."""

import pytest

from openpilot.common.params import Params
from openpilot.system.manager import process_config


@pytest.mark.parametrize(('setup', 'driver_view', 'started', 'expected'), [
  (True, False, False, {'camerad': True, 'webcamerad': True, 'dmonitoringmodeld': True,
                        'soundd': False, 'dmonitoringd': False}),
  (False, True, False, {'camerad': True, 'webcamerad': True, 'dmonitoringmodeld': True,
                        'soundd': True, 'dmonitoringd': True}),
  (False, False, False, {'camerad': False, 'webcamerad': False, 'dmonitoringmodeld': False,
                         'soundd': False, 'dmonitoringd': False}),
  (False, False, True, {'camerad': True, 'webcamerad': True, 'dmonitoringmodeld': True,
                        'soundd': True, 'dmonitoringd': True}),
])
def test_wheel_side_setup_process_scope(monkeypatch, tmp_path, setup, driver_view, started, expected):
  monkeypatch.setattr(process_config, 'camera_requested', lambda: False)
  params = Params(str(tmp_path))
  params.put_bool('IsWheelSideSetupEnabled', setup, block=True)
  params.put_bool('IsDriverViewEnabled', driver_view, block=True)
  processes = {proc.name: proc for proc in process_config.procs}

  assert {name: processes[name].should_run(started, params, None) for name in expected} == expected


def test_setup_param_is_registered_as_boolean(tmp_path):
  params = Params(str(tmp_path))
  params.put_bool('IsWheelSideSetupEnabled', True, block=True)
  assert params.get_bool('IsWheelSideSetupEnabled')


def test_cabin_model_recovers_if_cpu_power_state_races_startup():
  model = process_config.managed_processes['dmonitoringmodeld']
  assert getattr(model, 'restart_on_exit', False)
