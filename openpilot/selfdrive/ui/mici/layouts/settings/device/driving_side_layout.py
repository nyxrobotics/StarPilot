"""Parked-only wheel and traffic side settings for the comma four."""

from __future__ import annotations

from collections.abc import Callable
import time

import pyray as rl

from openpilot.selfdrive.ui.mici.onroad.cabin_camera_dialog import BaseCabinCameraDialog
from openpilot.selfdrive.ui.mici.widgets.button import BigButton
from openpilot.selfdrive.ui.mici.widgets.dialog import BigDialog
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

  VIEW_ENABLE_PARAM = "IsWheelSideSetupEnabled"
  CLEAR_DISTRACTION_ON_SHOW = False
  PUBLISH_MONITORING_ALERTS = False

  def __init__(self, settings: DrivingSideSettings, on_saved: Callable[[], None] | None = None):
    super().__init__()
    self.settings = settings
    self.on_saved = on_saved
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
    return rl.Rectangle(rect.x + (12 if action == "no" else rect.width - 116),
                        rect.y + rect.height - 70, 104, 60)

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
        if self.on_saved is not None:
          self.on_saved()
      else:
        gui_app.push_widget(BigDialog("", "Park and try saving the wheel side again."))

  def _render(self, rect: rl.Rectangle):
    # Draw the camera without the inherited DMoji, driver alerts, or warning sounds.
    rl.begin_scissor_mode(int(rect.x), int(rect.y), int(rect.width), int(rect.height))
    self._camera_view._render(rect)
    if not self._camera_view.frame:
      gui_label(rect, "Camera starting...", font_size=28, font_weight=FontWeight.MEDIUM,
                alignment=TextAlignment.CENTER)
    rl.end_scissor_mode()
    overlay = rl.Rectangle(rect.x, rect.y + rect.height - 82, rect.width, 82)
    rl.draw_rectangle_rec(overlay, rl.Color(0, 0, 0, 225))
    candidate = self.proposal.expire(time.monotonic()) if _parked() else None
    message = f"Wheel on {_label(candidate)}?" if candidate is not None else "Looking for driver..."
    gui_label(rl.Rectangle(rect.x + 120, overlay.y + 4, rect.width - 240, 74), message,
              font_size=22, font_weight=FontWeight.MEDIUM, alignment=TextAlignment.CENTER)
    for action, label, enabled in (("no", "NO", True), ("yes", "YES", candidate is not None)):
      bounds = self._button_rect(rect, action)
      color = rl.Color(38, 115, 55, 255) if action == "yes" and enabled else rl.Color(56, 56, 56, 255)
      rl.draw_rectangle_rounded(bounds, 0.2, 8, color)
      gui_label(bounds, label, font_size=25, font_weight=FontWeight.BOLD,
                alignment=TextAlignment.CENTER, color=rl.WHITE if enabled else rl.GRAY)


class _SideChoicePage(NavScroller):
  """Short choice list with a check on the currently saved option."""

  def __init__(self, settings: DrivingSideSettings, kind: str, on_choice, on_camera):
    super().__init__()
    self.settings = settings
    self.kind = kind
    self._check = gui_app.texture("icons_mici/settings/device/up_to_date.png", 64, 64)
    self._options: list[tuple[str | None, BigButton, str]] = []

    widgets: list[BigButton] = []
    if kind == "wheel":
      camera = BigButton("use camera", "review, then YES", gui_app.texture(CAMERA_ICON, 64, 64),
                         description="The cabin camera proposes the steering-wheel position. Tap YES to save it.")
      camera.set_click_callback(on_camera)
      camera.set_enabled(lambda: _parked() and not self.is_dismissing)
      widgets.append(camera)
      choices = (("right", "wheel on right", "set manually"),
                 ("left", "wheel on left", "set manually"),
                 (None, "automatic", "clear saved choice"))
    else:
      choices = ((None, "follow wheel", "default"),
                 ("left", "drive on left", "set manually"),
                 ("right", "drive on right", "set manually"))

    for side, title, detail in choices:
      button = BigButton(title, detail)
      button.set_click_callback(lambda selected=side: on_choice(kind, selected))
      button.set_enabled(lambda: _parked() and not self.is_dismissing)
      widgets.append(button)
      self._options.append((side, button, detail))
    self._scroller.add_widgets(widgets)

  def _update_state(self):
    super()._update_state()
    state = self.settings.snapshot()
    selected = (state.wheel_side if state.wheel_saved else None) if self.kind == "wheel" else (
      state.traffic_side if state.traffic_saved else None)
    for side, button, detail in self._options:
      active = side == selected
      button.set_icon(self._check if active else None)
      button.set_value("selected" if active else detail)


class DrivingSideLayoutMici(NavScroller):
  def __init__(self):
    super().__init__()
    self.settings = DrivingSideSettings(ui_state.params, _parked)
    self._camera_dialog: WheelSideCameraDialog | None = None
    self._wheel_choices: _SideChoicePage | None = None
    self._traffic_choices: _SideChoicePage | None = None

    self._wheel_button = BigButton("steering wheel", "", gui_app.texture(CAMERA_ICON, 64, 64),
                                   description="Set the steering-wheel position by camera or manually.")
    self._wheel_button.set_click_callback(lambda: self._open_choices("wheel"))
    self._wheel_button.set_enabled(_parked)

    self._traffic_button = BigButton("traffic side", "", gui_app.texture(INFO_ICON, 64, 64),
                                     description="By default, a right-side steering wheel means driving on the left. You can choose either traffic side.")
    self._traffic_button.set_click_callback(lambda: self._open_choices("traffic"))
    self._traffic_button.set_enabled(_parked)
    self._scroller.add_widgets([self._wheel_button, self._traffic_button])

  def _update_state(self):
    super()._update_state()
    state = self.settings.snapshot()
    wheel_source = "saved" if state.wheel_saved else "automatic"
    traffic_source = "manual" if state.traffic_saved else "default" if state.wheel_saved else "automatic"
    self._wheel_button.set_value(f"{_label(state.wheel_side)} ({wheel_source})")
    self._traffic_button.set_value(f"{_label(state.traffic_side)} ({traffic_source})")

  def _open_choices(self, kind: str):
    if not _parked():
      return
    if kind == "wheel":
      if self._wheel_choices is None:
        self._wheel_choices = _SideChoicePage(self.settings, kind, self._choose, self._show_camera)
      page = self._wheel_choices
    else:
      if self._traffic_choices is None:
        self._traffic_choices = _SideChoicePage(self.settings, kind, self._choose, self._show_camera)
      page = self._traffic_choices
    gui_app.push_widget(page)

  def _show_camera(self):
    if _parked():
      if self._camera_dialog is None:
        self._camera_dialog = WheelSideCameraDialog(self.settings, on_saved=self._camera_saved)
      gui_app.push_widget(self._camera_dialog)

  def _camera_saved(self):
    if self._wheel_choices is not None and gui_app.widget_in_stack(self._wheel_choices):
      self._wheel_choices.dismiss()

  def _choose(self, kind: str, side: str | None):
    if not _parked():
      return
    page = self._wheel_choices if kind == "wheel" else self._traffic_choices
    try:
      if kind == "wheel":
        result = self.settings.clear_wheel_side() if side is None else self.settings.save_wheel_side(side)
      else:
        result = self.settings.clear_traffic_side() if side is None else self.settings.save_traffic_side(side)
    except (OSError, RuntimeError, ValueError):
      result = False
    if result:
      if page is not None and gui_app.widget_in_stack(page):
        page.dismiss()
    else:
      gui_app.push_widget(BigDialog("", "Park and try saving the driving side again."))
