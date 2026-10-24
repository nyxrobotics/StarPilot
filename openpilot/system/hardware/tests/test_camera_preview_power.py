"""Camera previews keep inference cores powered while blocking onroad startup."""

import pytest

from openpilot.common.params import Params
from openpilot.system.hardware.hardwared import camera_preview_active, should_enter_power_save


@pytest.mark.parametrize(('driver_view', 'wheel_setup', 'expected'), [
  (False, False, False),
  (True, False, True),
  (False, True, True),
  (True, True, True),
])
def test_either_camera_preview_keeps_dark_screen_powered(tmp_path, driver_view, wheel_setup, expected):
  params = Params(str(tmp_path))
  params.put_bool('IsDriverViewEnabled', driver_view, block=True)
  params.put_bool('IsWheelSideSetupEnabled', wheel_setup, block=True)

  preview = camera_preview_active(params)
  assert preview is expected
  assert should_enter_power_save(False, False, 0., preview) is (not expected)


def test_existing_ignition_started_and_bright_screen_power_rules_remain():
  assert should_enter_power_save(False, False, 0., False)
  assert not should_enter_power_save(True, False, 0., False)
  assert not should_enter_power_save(False, True, 0., False)
  assert not should_enter_power_save(False, False, 25., False)
