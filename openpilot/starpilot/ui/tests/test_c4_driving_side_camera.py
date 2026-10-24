"""C4 cabin-camera proposals need a fresh, explicit YES before saving."""

import unittest
from unittest.mock import Mock, patch

import pyray as rl

from openpilot.selfdrive.ui.mici.layouts.settings.device import driving_side_layout as page
from openpilot.selfdrive.ui.mici.onroad.cabin_camera_dialog import BaseCabinCameraDialog
from openpilot.starpilot.ui.driving_side_settings import WheelSideProposal
from openpilot.system.ui.lib.application import MousePos


class TestC4DrivingSideCamera(unittest.TestCase):
  def test_no_dismisses_and_yes_saves_only_a_fresh_confirmed_proposal(self):
    settings = Mock()
    settings.save_wheel_side.return_value = True
    dialog = page.WheelSideCameraDialog.__new__(page.WheelSideCameraDialog)
    dialog.settings = settings
    dialog.proposal = WheelSideProposal()
    dialog._camera_view = None
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

      dialog._handle_mouse_press(MousePos(490, 205))
      dialog._handle_mouse_release(MousePos(490, 205))
      settings.save_wheel_side.assert_called_once_with("right")

    settings.save_wheel_side.reset_mock()
    with patch.object(page, "_parked", return_value=True), \
         patch.object(BaseCabinCameraDialog, "_handle_mouse_release"), \
         patch.object(page.time, "monotonic", side_effect=[1.5, 2.2]):
      dialog._handle_mouse_press(MousePos(490, 205))
      dialog._handle_mouse_release(MousePos(490, 205))
    settings.save_wheel_side.assert_not_called()


if __name__ == "__main__":
  unittest.main()
