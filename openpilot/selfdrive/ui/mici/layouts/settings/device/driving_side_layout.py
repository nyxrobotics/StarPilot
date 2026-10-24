"""Parked-only wheel and traffic side settings for the comma four."""

from __future__ import annotations

import time

import pyray as rl

from openpilot.selfdrive.ui.mici.onroad.cabin_camera_dialog import BaseCabinCameraDialog
from openpilot.selfdrive.ui.mici.widgets.button import BigButton, GreyBigButton
from openpilot.selfdrive.ui.mici.widgets.dialog import BigConfirmationDialog, BigDialog
from openpilot.selfdrive.ui.ui_state import device, ui_state
from openpilot.starpilot.ui.driving_side_settings import DrivingSideSettings, WheelSideProposal
from openpilot.starpilot.ui.slc_offset_feature import native_parked
from openpilot.system.ui.lib.application import FontWeight, MousePos, TextAlignment, gui_app
from openpilot.system.ui.widgets.label import gui_label
from openpilot.system.ui.widgets.nav_widget import NavWidget
from openpilot.system.ui.widgets.scroller import NavScroller

CAMERA_ICON = "icons_mici/settings/device/cameras.png"
INFO_ICON = "icons_mici/settings/device/info.png"


def _parked() -> bool:
  return native_parked(ui_state) and not ui_state.engaged


def _label(side: str | None) -> str:
  return {"left": "left", "right": "right"}.get(side, "unknown")


class WheelSideCameraDialog(NavWidget, BaseCabinCameraDialog):
  """Show a fresh cabin-camera proposal; only a separate YES tap saves it."""

  def __init__(self, settings: DrivingSideSettings):
    super().__init__()
    self.settings = settings
    self.proposal = WheelSideProposal()
    self._last_frame = 0
    self._pressed: tuple[str, str | None] | None = None
    self._active = False
    device.add_interactive_timeout_callback(self._on_timeout)

  def _on_timeout(self):
    if self._active and self in gui_app._nav_stack:
      gui_app.pop_widget(gui_app._nav_stack.index(self))

  def show_event(self):
    super().show_event()
    self._active = True
    self.proposal.reset()
    self._last_frame = ui_state.sm.recv_frame.get("driverStateV2", 0)
    self._pressed = None

  def hide_event(self):
    self._active = False
    self.proposal.reset()
    self._pressed = None
    super().hide_event()

  def _update_state(self):
    super()._update_state()
    now = time.monotonic()
    self.proposal.expire(now)
    if not _parked():
      self.proposal.reset()
      return
    sm = ui_state.sm
    frame = sm.recv_frame.get("driverStateV2", 0)
    if frame <= self._last_frame:
      return
    self._last_frame = frame
    frame_age = now - sm.recv_time.get("driverStateV2", 0)
    if not sm.valid.get("driverStateV2", False) or not 0 <= frame_age <= 0.35:
      self.proposal.reset()
      return
    driver = sm["driverStateV2"]
    self.proposal.observe(driver.wheelOnRightProb, driver.leftDriverData.faceProb,
                          driver.rightDriverData.faceProb, now)

  @staticmethod
  def _button_rect(rect: rl.Rectangle, action: str) -> rl.Rectangle:
    return rl.Rectangle(rect.x + (10 if action == "no" else rect.width - 90),
                        rect.y + rect.height - 59, 80, 49)

  def _target(self, pos: MousePos) -> str | None:
    for action in ("no", "yes"):
      if rl.check_collision_point_rec(pos, self._button_rect(self._rect, action)):
        return action
    return None

  def _handle_mouse_press(self, pos: MousePos):
    action = self._target(pos)
    candidate = self.proposal.expire(time.monotonic())
    self._pressed = (action, candidate) if action == "no" or action == "yes" and candidate is not None and _parked() else None

  def _handle_mouse_release(self, pos: MousePos):
    super()._handle_mouse_release(pos)
    pressed = self._pressed
    self._pressed = None
    if pressed is None or self._target(pos) != pressed[0]:
      return
    if pressed[0] == "no":
      gui_app.pop_widget()
    elif pressed[1] is not None and pressed[1] == self.proposal.expire(time.monotonic()):
      try:
        saved = self.settings.save_wheel_side(pressed[1])
      except (OSError, RuntimeError, ValueError):
        saved = False
      if saved:
        gui_app.pop_widget()
      else:
        gui_app.push_widget(BigDialog("", "Park and try saving the wheel side again."))

  def _render(self, rect: rl.Rectangle):
    super()._render(rect)
    overlay = rl.Rectangle(rect.x, rect.y + rect.height - 69, rect.width, 69)
    rl.draw_rectangle_rec(overlay, rl.Color(0, 0, 0, 225))
    candidate = self.proposal.expire(time.monotonic()) if _parked() else None
    message = f"Wheel on {_label(candidate)}?" if candidate is not None else "Look toward camera. Waiting for a clear result."
    gui_label(rl.Rectangle(rect.x + 96, overlay.y + 4, rect.width - 192, 61), message,
              font_size=22, font_weight=FontWeight.MEDIUM, alignment=TextAlignment.CENTER)
    for action, label, enabled in (("no", "NO", True), ("yes", "YES", candidate is not None)):
      bounds = self._button_rect(rect, action)
      color = rl.Color(38, 115, 55, 255) if action == "yes" and enabled else rl.Color(56, 56, 56, 255)
      rl.draw_rectangle_rounded(bounds, 0.2, 8, color)
      gui_label(bounds, label, font_size=25, font_weight=FontWeight.BOLD,
                alignment=TextAlignment.CENTER, color=rl.WHITE if enabled else rl.GRAY)


class DrivingSideLayoutMici(NavScroller):
  def __init__(self):
    super().__init__()
    self.settings = DrivingSideSettings(ui_state.params, _parked)
    self._camera_dialog: WheelSideCameraDialog | None = None
    info = gui_app.texture(INFO_ICON, 64, 64)
    camera = gui_app.texture(CAMERA_ICON, 64, 64)

    self._wheel_status = GreyBigButton("wheel side", "", info)
    self._traffic_status = GreyBigButton("road side", "", info)

    camera_button = BigButton("detect wheel", "camera proposal", camera,
                              description="While parked, show the cabin camera. A clear result is only saved after you tap YES.")
    camera_button.set_click_callback(self._show_camera)
    camera_button.set_enabled(_parked)

    wheel_right = BigButton("wheel on right", "manual choice", camera)
    wheel_right.set_click_callback(lambda: self._confirm("wheel", "right"))
    wheel_left = BigButton("wheel on left", "manual choice", camera)
    wheel_left.set_click_callback(lambda: self._confirm("wheel", "left"))
    wheel_auto = BigButton("wheel automatic", "clear saved choice", camera)
    wheel_auto.set_click_callback(lambda: self._confirm("wheel", None))

    traffic_left = BigButton("drive on left", "manual road side", info)
    traffic_left.set_click_callback(lambda: self._confirm("traffic", "left"))
    traffic_right = BigButton("drive on right", "manual road side", info)
    traffic_right.set_click_callback(lambda: self._confirm("traffic", "right"))
    traffic_default = BigButton("road side default", "follow wheel side", info)
    traffic_default.set_click_callback(lambda: self._confirm("traffic", None))

    for button in (wheel_right, wheel_left, wheel_auto, traffic_left, traffic_right, traffic_default):
      button.set_enabled(_parked)
    self._scroller.add_widgets([self._wheel_status, camera_button, wheel_right, wheel_left, wheel_auto,
                                self._traffic_status, traffic_left, traffic_right, traffic_default])

  def _update_state(self):
    super()._update_state()
    state = self.settings.snapshot()
    wheel_source = "saved" if state.wheel_saved else "auto"
    traffic_source = "saved" if state.traffic_saved else "default" if state.wheel_saved else "auto"
    self._wheel_status.set_value(f"{_label(state.wheel_side)} ({wheel_source})")
    self._traffic_status.set_value(f"{_label(state.traffic_side)} ({traffic_source})")

  def _show_camera(self):
    if _parked():
      if self._camera_dialog is None:
        self._camera_dialog = WheelSideCameraDialog(self.settings)
      gui_app.push_widget(self._camera_dialog)

  def _confirm(self, kind: str, side: str | None):
    if not _parked():
      return
    noun = "wheel" if kind == "wheel" else "road"
    text = f"restore {noun} default" if side is None else f"save {side} {noun} side"

    def save():
      try:
        if kind == "wheel":
          result = self.settings.clear_wheel_side() if side is None else self.settings.save_wheel_side(side)
        else:
          result = self.settings.clear_traffic_side() if side is None else self.settings.save_traffic_side(side)
      except (OSError, RuntimeError, ValueError):
        result = False
      if not result:
        gui_app.push_widget(BigDialog("", "Park and try saving the driving side again."))

    icon = gui_app.texture(CAMERA_ICON if kind == "wheel" else INFO_ICON, 64, 64)
    gui_app.push_widget(BigConfirmationDialog(text, icon, save))
