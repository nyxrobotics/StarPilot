"""C4 cabin-camera proposals need a fresh, explicit YES before saving."""

import unittest
from typing import cast
from unittest.mock import Mock, patch

import pyray as rl

from openpilot.selfdrive.ui.mici.layouts.settings.device import driving_side_layout as page
from openpilot.selfdrive.ui.mici.onroad.cabin_camera_dialog import BaseCabinCameraDialog, CabinCameraView
from openpilot.starpilot.ui.driving_side_settings import WheelSideProposal
from openpilot.system.ui.lib.application import MousePos


class TestC4DrivingSideCamera(unittest.TestCase):
  def test_ignition_closes_preview_before_it_blocks_onroad_startup(self):
    dialog = page.WheelSideCameraDialog.__new__(page.WheelSideCameraDialog)
    dialog.proposal = WheelSideProposal()
    dialog._active = True
    dialog._camera_view = cast(CabinCameraView, None)

    with patch.object(page.NavWidget, "_update_state"), \
         patch.object(page, "_parked", return_value=True), \
         patch.object(page.ui_state, "ignition", True), \
         patch.object(page.gui_app, "_nav_stack", [object(), dialog]), \
         patch.object(page.gui_app, "pop_widget") as pop:
      dialog._update_state()

    pop.assert_called_once_with(1)

  def test_preview_uses_camera_without_driver_monitoring_alerts(self):
    dialog = page.WheelSideCameraDialog.__new__(page.WheelSideCameraDialog)
    dialog._camera_view = Mock(frame=object())
    dialog._pm = Mock()
    dialog.proposal = WheelSideProposal()
    rect = rl.Rectangle(0, 0, 536, 240)

    with patch.object(BaseCabinCameraDialog, "_render", side_effect=AssertionError("driver monitoring overlay used")), \
         patch.object(page, "_parked", return_value=True), \
         patch.object(page.rl, "begin_scissor_mode"), patch.object(page.rl, "end_scissor_mode"), \
         patch.object(page.rl, "draw_rectangle_rec"), patch.object(page.rl, "draw_rectangle_rounded"), \
         patch.object(page, "gui_label"):
      dialog._render(rect)

    dialog._camera_view._render.assert_called_once_with(rect)
    dialog._pm.send.assert_not_called()

  def test_no_dismisses_and_yes_saves_only_a_fresh_confirmed_proposal(self):
    settings = Mock()
    settings.save_wheel_side.return_value = True
    dialog = page.WheelSideCameraDialog.__new__(page.WheelSideCameraDialog)
    dialog.settings = settings
    dialog.on_saved = Mock()
    dialog.proposal = WheelSideProposal()
    dialog._camera_view = cast(CabinCameraView, None)
    dialog._rect = rl.Rectangle(0, 0, 536, 240)
    dialog._pressed = None
    for i in range(20):
      dialog.proposal.observe(0.95, 0.1, 0.9, i * 0.07)
    settings.save_wheel_side.assert_not_called()

    with patch.object(page, "_parked", return_value=True), \
         patch.object(BaseCabinCameraDialog, "_handle_mouse_release"), \
         patch.object(page.gui_app, "pop_widget") as pop, \
         patch.object(page.time, "monotonic", return_value=1.5):
      dialog._handle_mouse_press(MousePos(50, 205))
      dialog._handle_mouse_release(MousePos(50, 205))
      pop.assert_called_once_with()
      settings.save_wheel_side.assert_not_called()
      dialog.on_saved.assert_not_called()

      dialog._handle_mouse_press(MousePos(490, 205))
      dialog._handle_mouse_release(MousePos(490, 205))
      settings.save_wheel_side.assert_called_once_with("right")
      dialog.on_saved.assert_called_once_with()

    settings.save_wheel_side.reset_mock()
    with patch.object(page, "_parked", return_value=True), \
         patch.object(BaseCabinCameraDialog, "_handle_mouse_release"), \
         patch.object(page.time, "monotonic", side_effect=[1.5, 2.2]):
      dialog._handle_mouse_press(MousePos(490, 205))
      dialog._handle_mouse_release(MousePos(490, 205))
    settings.save_wheel_side.assert_not_called()


if __name__ == "__main__":
  unittest.main()
