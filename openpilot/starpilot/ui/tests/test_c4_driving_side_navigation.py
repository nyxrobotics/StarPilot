"""The compact driving-side menu exposes two categories and short choice pages."""

import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pyray as rl

from openpilot.common.params import Params
from openpilot.selfdrive.ui.mici.layouts.settings.device import driving_side_layout as page


class TestC4DrivingSideNavigation(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.params = Params(self.directory.name)
    self.parked = Mock(return_value=True)
    for target, attribute, value in (
      (page, "ui_state", SimpleNamespace(params=self.params)),
      (page, "_parked", self.parked),
      (page.gui_app, "texture", Mock(return_value=rl.Texture())),
      (page.gui_app, "font", Mock(return_value=rl.Font())),
    ):
      context = patch.object(target, attribute, value)
      context.start()
      self.addCleanup(context.stop)
    self.panel = page.DrivingSideLayoutMici()

  def test_two_categories_show_current_value_and_manual_choice_saves_without_slider(self):
    self.assertEqual([card.text for card in self.panel._scroller.items], ["steering wheel", "traffic side"])
    self.panel._update_state()
    self.assertEqual(self.panel._wheel_button.value, "unknown (automatic)")
    self.assertEqual(self.panel._traffic_button.value, "unknown (automatic)")

    with patch.object(page.gui_app, "push_widget") as push:
      self.panel._wheel_button._click_callback()
      wheel = self.panel._wheel_choices
      self.assertIsNotNone(wheel)
      push.assert_called_once_with(wheel)
    self.assertEqual([card.text for card in wheel._scroller.items],
                     ["use camera", "wheel on right", "wheel on left", "automatic"])

    with patch.object(page.gui_app, "widget_in_stack", return_value=True), patch.object(wheel, "dismiss") as dismiss:
      wheel._options[0][1]._click_callback()
      dismiss.assert_called_once_with()
    self.assertEqual(self.params.get("DriverSide"), "right")
    self.assertIsNone(self.params.get("TrafficSide"))
    self.panel._update_state()
    self.assertEqual(self.panel._wheel_button.value, "right (saved)")
    self.assertEqual(self.panel._traffic_button.value, "left (default)")
    wheel._update_state()
    self.assertIs(wheel._options[0][1]._txt_icon, wheel._check)
    self.assertIsNone(wheel._options[2][1]._txt_icon)

  def test_traffic_side_override_and_default_are_clear_from_choices(self):
    self.params.put("DriverSide", "right", block=True)
    with patch.object(page.gui_app, "push_widget") as push:
      self.panel._traffic_button._click_callback()
      traffic = self.panel._traffic_choices
      self.assertIsNotNone(traffic)
      push.assert_called_once_with(traffic)
    self.assertEqual([card.text for card in traffic._scroller.items],
                     ["follow wheel", "drive on left", "drive on right"])

    traffic._update_state()
    self.assertIs(traffic._options[0][1]._txt_icon, traffic._check)
    with patch.object(page.gui_app, "widget_in_stack", return_value=True), patch.object(traffic, "dismiss") as dismiss:
      traffic._options[2][1]._click_callback()
      dismiss.assert_called_once_with()
      self.assertEqual(self.params.get("TrafficSide"), "right")
      traffic._update_state()
      self.assertIs(traffic._options[2][1]._txt_icon, traffic._check)
      traffic._options[0][1]._click_callback()
      self.assertIsNone(self.params.get("TrafficSide"))
    self.panel._update_state()
    self.assertEqual(self.panel._traffic_button.value, "left (default)")

  def test_camera_confirmation_returns_to_categories_but_no_does_not(self):
    with patch.object(page.gui_app, "push_widget"):
      self.panel._open_choices("wheel")
    wheel = self.panel._wheel_choices
    with patch.object(page, "WheelSideCameraDialog") as camera_type, patch.object(page.gui_app, "push_widget"):
      wheel._scroller.items[0]._click_callback()
      on_saved = camera_type.call_args.kwargs["on_saved"]
      with patch.object(page.gui_app, "widget_in_stack", return_value=True), patch.object(wheel, "dismiss") as dismiss:
        on_saved()
        dismiss.assert_called_once_with()

    self.parked.return_value = False
    with patch.object(page.gui_app, "push_widget") as push:
      self.panel._open_choices("traffic")
      self.panel._choose("wheel", "left")
      push.assert_not_called()
    self.assertIsNone(self.params.get("DriverSide"))


if __name__ == "__main__":
  unittest.main()
