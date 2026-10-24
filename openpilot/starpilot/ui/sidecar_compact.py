"""C4 (compact) page for the X10 sidecar: the X10's address, port and token, the on/off switch and a live check of the X10.

Settings > X10 sidecar. Every edit is a card: tapping it opens the on-screen keyboard (digits first for the address and port, masked for
the token) with a line under the text that says what is wrong while typing, and the keyboard's check mark applies the change. The
switch applies when tapped. Changes are written to /data/sidecar.json by SidecarFlow off the UI thread and take effect within a
second or two without a restart. Nothing can be edited while driving. The state of the X10 comes from a background GET /status.
"""

from __future__ import annotations

from collections.abc import Callable

import pyray as rl

from openpilot.selfdrive.ui.mici.widgets.button import BigButton, BigToggle, GreyBigButton
from openpilot.selfdrive.ui.mici.widgets.dialog import BigConfirmationDialog, BigDialog, BigInputDialog, SettingDescriptionDialog
from openpilot.starpilot.ui.sidecar_flow import (
  Feedback, SidecarControls, SidecarView, describe, group_token, host_feedback, port_feedback, token_feedback,
)
from openpilot.system.ui.lib.application import FontWeight, MousePos, gui_app
from openpilot.system.ui.widgets.label import UnifiedLabel
from openpilot.system.ui.widgets.scroller import NavScroller

MESSAGE_SIZE = 22
MESSAGE_HEIGHT = 24
MESSAGE_TOP = 47        # below the text row (which ends near 46) and above the keyboard (which starts at 70)
MESSAGE_LEFT = 106      # where the typed text starts: right of the check mark, left of the backspace key
MESSAGE_WIDTH = 330
GREY = rl.Color(255, 255, 255, int(255 * 0.55))
ORANGE = rl.Color(255, 176, 96, 255)
TOKEN_LINE_GROUPS = 4   # groups of four characters per card when the token is shown

DESCRIPTIONS = {
  "toggle": "Use the X10 head unit as the route source. This comma asks the X10 for routes and the X10 sends destinations here. " +
            "It needs the X10's address and a token that matches the X10 app.",
  "address": "The X10's IPv4 address on your Wi-Fi network, not this comma's address. Give the X10 a fixed address in your router " +
             "so that it does not change.",
  "port": "The X10 route server's port. It is 8765 unless you changed it in the X10 app.",
  "token": "A shared secret of 10 to 128 characters: letters, digits and symbols, no spaces. Enter the same value in the X10 app. " +
           "A random value of 24 or more characters is safer on a shared network.",
  "reveal": "Shows the saved token so that you can compare it with the X10 app. Swipe down to hide it again.",
  "test": "Asks the X10 for its status. It shows whether the X10 answers and its route engine is ready. It cannot check the token.",
  "clear": "Turns the X10 sidecar off and removes the saved token. The address and port stay.",
}


class SidecarInputDialog(BigInputDialog):
  """The compact keyboard dialog with a check line, a digits-first keyboard and tap-the-text to show or hide a secret."""

  TEXT_INPUT_SIZE = 30

  def __init__(self, hint: str, default_text: str, *, feedback: Callable[[str], Feedback], confirm_callback: Callable[[str], None],
               numeric: bool = False, secret: bool = False):
    super().__init__(hint, default_text, minimum_length=1, confirm_callback=confirm_callback,
                     text_validator=lambda text: feedback(text).valid, password_mode=secret)
    self._feedback = feedback
    self._secret = secret
    self._numbers_pending = numeric
    self._hint_label.set_font_size(self.TEXT_INPUT_SIZE)
    self._message = UnifiedLabel("", font_size=MESSAGE_SIZE, font_weight=FontWeight.ROMAN, text_color=GREY, elide=True, wrap_text=False)

  @property
  def revealing(self) -> bool:
    return self._secret and not self._password_mode

  def message(self) -> Feedback:
    text = self._keyboard.text()
    result = self._feedback(text)
    if self._secret and not result.error:
      action = "hide" if self.revealing else "show"
      return result._replace(text=f"{result.text}, tap text to {action}" if text else f"tap the text to {action} it")
    return result

  def _reveal_rect(self) -> rl.Rectangle:
    """The text row between the check mark and the backspace key."""
    left = self._top_left_button_rect.x + self._top_left_button_rect.width
    return rl.Rectangle(left, self._rect.y, self._top_right_button_rect.x - left, self._top_left_button_rect.height)

  def toggle_reveal(self) -> None:
    if self._secret:
      self._password_mode = not self._password_mode

  def _render(self, _):
    super()._render(_)
    if self._numbers_pending and self._keyboard._initialized:
      # Switch layers only after the keys were laid out once: a switch before that gives them the wrong touch positions.
      self._numbers_pending = False
      self._keyboard._set_keys(self._keyboard._special_keys)
    result = self.message()
    self._message.set_text(result.text)
    self._message.set_text_color(ORANGE if result.error else GREY)
    self._message.render(rl.Rectangle(self._rect.x + MESSAGE_LEFT, self._rect.y + MESSAGE_TOP, MESSAGE_WIDTH, MESSAGE_HEIGHT))

  def _handle_mouse_press(self, mouse_pos: MousePos):
    super()._handle_mouse_press(mouse_pos)
    if self._secret and not self.is_dismissing and rl.check_collision_point_rec(mouse_pos, self._reveal_rect()):
      self.toggle_reveal()


class SidecarPage(NavScroller):
  """The X10 sidecar cards. All state lives in the SidecarFlow; this only shows its view and forwards taps."""

  def __init__(self, flow: SidecarControls):
    super().__init__()
    self.flow = flow
    self.view = SidecarView()
    self._status = GreyBigButton("x10 sidecar", "")
    self._warning = GreyBigButton("", "")
    self._toggle = BigToggle("use the x10", "", False, toggle_callback=self._toggled, description=DESCRIPTIONS["toggle"])
    self._address = BigButton("x10 address", "", description=DESCRIPTIONS["address"])
    self._token = BigButton("token", "", description=DESCRIPTIONS["token"])
    self._port = BigButton("port", "", description=DESCRIPTIONS["port"])
    self._reveal = BigButton("show token", "tap to reveal", description=DESCRIPTIONS["reveal"])
    self._test = BigButton("test connection", "", description=DESCRIPTIONS["test"])
    self._clear_card = BigButton("clear", "remove the token", description=DESCRIPTIONS["clear"])
    for card, action in ((self._address, self._edit_address), (self._token, self._edit_token), (self._port, self._edit_port),
                         (self._reveal, self._show_token), (self._test, self._test_now), (self._clear_card, self._confirm_clear)):
      card.set_click_callback(action)
    for card in (self._toggle, self._address, self._token, self._port):
      card.set_enabled(self._can_edit)
    self._clear_card.set_enabled(lambda: self._can_edit() and self.view.can_clear)
    self._reveal.set_enabled(lambda: self.view.editable)  # the token is shown only while parked, like every change
    self._warning.set_visible(lambda: bool(self._words.warning))
    self._reveal.set_visible(lambda: self.view.token_length > 0)
    self._words = describe(self.view)
    self._scroller.add_widgets([self._status, self._warning, self._toggle, self._address, self._token, self._port, self._reveal,
                                self._test, self._clear_card])

  def _can_edit(self) -> bool:
    return self.view.editable and not self.view.busy

  def show_event(self):
    super().show_event()
    self.flow.activate()

  def hide_event(self):
    self.flow.deactivate()
    super().hide_event()

  def _update_state(self):
    super()._update_state()
    self.apply(self.flow.update())
    notice = self.flow.take_notice()
    if notice:
      gui_app.push_widget(BigDialog("", notice))

  def apply(self, view: SidecarView) -> None:
    """Show `view`: only a card whose words changed is touched, so nothing is re-measured every frame."""
    self.view = view
    words = self._words = describe(view)
    self._set(self._status, words.status_title.lower(), words.status_detail)
    self._set(self._warning, "", words.warning)
    self._set(self._toggle, None, words.toggle_value.lower())
    self._set(self._address, None, words.address)
    self._set(self._token, None, words.token)
    self._set(self._port, None, words.port)
    self._set(self._test, None, words.test)
    if not view.busy:  # while a change is being written the switch keeps what the user just chose
      self._toggle.set_checked(view.enabled)

  @staticmethod
  def _set(card: BigButton, text: str | None, value: str) -> None:
    if text is not None and card.get_text() != text:
      card.set_text(text)
    if card.get_value() != value:
      card.set_value(value)

  def _toggled(self, checked: bool) -> None:
    self.flow.set_enabled(checked)

  def _edit_address(self) -> None:
    gui_app.push_widget(SidecarInputDialog("x10 address", self.view.host, feedback=host_feedback, numeric=True,
                                           confirm_callback=self._save_address))

  def _edit_port(self) -> None:
    gui_app.push_widget(SidecarInputDialog("port", str(self.view.port), feedback=port_feedback, numeric=True,
                                           confirm_callback=self._save_port))

  def _edit_token(self) -> None:
    gui_app.push_widget(SidecarInputDialog("token (10-128)", "", feedback=token_feedback, secret=True,
                                           confirm_callback=self._save_token))

  # The keyboard hands over the text after it has slid away; the flow checks it again and reports a refusal as a notice.
  def _save_address(self, text: str) -> None:
    self.flow.set_host(text)

  def _save_port(self, text: str) -> None:
    self.flow.set_port(text)

  def _save_token(self, text: str) -> None:
    self.flow.set_token(text)

  def _clear(self) -> None:
    self.flow.clear()

  def _show_token(self) -> None:
    if not self.view.editable:
      return
    token = self.flow.reveal_token()
    if not token:
      gui_app.push_widget(BigDialog("", "No token is saved."))
      return
    step = TOKEN_LINE_GROUPS * 4
    lines = "\n".join(group_token(token[index:index + step]) for index in range(0, len(token), step))
    gui_app.push_widget(SettingDescriptionDialog("token", lines))

  def _test_now(self) -> None:
    self.flow.test_now()

  def _confirm_clear(self) -> None:
    if not (self._can_edit() and self.view.can_clear):
      return
    gui_app.push_widget(BigConfirmationDialog("slide to clear", gui_app.texture("icons_mici/settings/device/uninstall.png", 64, 64),
                                              self._clear, red=True))


class SidecarCompact:
  """The compact Settings entry: builds the page for the session's flow."""

  def __init__(self, flow: SidecarControls):
    self.flow = flow

  def open(self) -> None:
    gui_app.push_widget(SidecarPage(self.flow))
