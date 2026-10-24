"""The wheel-side preview cannot enable driver-monitoring demo or clear its state."""

from types import SimpleNamespace
from unittest.mock import Mock, call, patch

from openpilot.selfdrive.ui.mici.layouts.settings.device.driving_side_layout import WheelSideCameraDialog
from openpilot.selfdrive.ui.mici.onroad import cabin_camera_dialog as camera


class WheelSetupPreview(camera.BaseCabinCameraDialog):
  VIEW_ENABLE_PARAM = 'IsWheelSideSetupEnabled'
  CLEAR_DISTRACTION_ON_SHOW = False
  PUBLISH_MONITORING_ALERTS = False


def test_actual_wheel_setup_uses_isolated_camera_mode():
  assert WheelSideCameraDialog.VIEW_ENABLE_PARAM == "IsWheelSideSetupEnabled"
  assert not WheelSideCameraDialog.CLEAR_DISTRACTION_ON_SHOW
  assert not WheelSideCameraDialog.PUBLISH_MONITORING_ALERTS


def test_base_preview_mode_is_overridable_without_changing_normal_cabin_view():
  params = Mock()
  device = Mock()

  with patch.object(camera, 'ui_state', SimpleNamespace(params=params)), \
       patch.object(camera, 'device', device), \
       patch.object(camera.messaging, 'PubMaster') as pubmaster:
    setup = WheelSetupPreview.__new__(WheelSetupPreview)
    setup._children = []
    setup._pm = None
    setup.show_event()
    setup._handle_mouse_release(None)
    setup.hide_event()

    assert params.put_bool.call_args_list == [
      call("IsWheelSideSetupEnabled", True),
      call("IsWheelSideSetupEnabled", False),
    ]
    params.remove.assert_not_called()
    pubmaster.assert_not_called()

    params.reset_mock()
    normal = camera.BaseCabinCameraDialog.__new__(camera.BaseCabinCameraDialog)
    normal._children = []
    normal._pm = None
    normal.show_event()
    normal._handle_mouse_release(None)
    normal.hide_event()

    assert params.put_bool.call_args_list == [
      call("IsDriverViewEnabled", True),
      call("IsDriverViewEnabled", False),
    ]
    assert params.remove.call_count == 2
    pubmaster.assert_called_once_with(['selfdriveState'])
