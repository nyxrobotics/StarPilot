"""C3 (large) page for the X10 sidecar: the same SidecarFlow as the compact page, drawn like the Galaxy connection page.

Device > X10 sidecar > OPEN. Rows show the on/off state, the X10's address, the port and the token (never its text); Change opens the large
keyboard (the token's has the eye to show and hide it). The words come from sidecar_flow.describe(), the same as on the comma four.
"""

from __future__ import annotations

from collections.abc import Callable

import pyray as rl

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.ui.sidecar_flow import (
  Feedback, SidecarControls, SidecarView, describe, group_token, host_feedback, port_feedback, token_feedback,
)
from openpilot.system.ui.lib.application import FontWeight, TextAlignment, TextAlignmentVertical, gui_app
from openpilot.system.ui.widgets import DialogResult, Widget
from openpilot.system.ui.widgets.button import Button, ButtonStyle
from openpilot.system.ui.widgets.confirm_dialog import ConfirmDialog, alert_dialog
from openpilot.system.ui.widgets.keyboard import Keyboard
from openpilot.system.ui.widgets.label import UnifiedLabel

LEFT = 140
LABEL_WIDTH = 560
VALUE_LEFT = 720
BUTTONS_LEFT = 1450
BUTTON_WIDTH = 270
BUTTON_HEIGHT = 92
BUTTON_GAP = 24
ROW_TOP = 430
ROW_HEIGHT = 108
WHITE = rl.Color(255, 255, 255, 255)
GREY = rl.Color(190, 190, 190, 255)
ORANGE = rl.Color(255, 176, 96, 255)
TOKEN_LINE_GROUPS = 4


class SidecarKeyboard(Keyboard):
  """The large keyboard with a live line under its title and a check key that stays off until the text is acceptable."""

  NEVER = 1 << 30  # a minimum length nobody reaches: the enter key stays disabled

  def __init__(self, title: str, rule: str, feedback: Callable[[str], Feedback], *, minimum: int, maximum: int, secret: bool = False,
               numeric: bool = False):
    super().__init__(max_text_size=maximum, min_text_size=minimum, password_mode=secret, show_password_toggle=secret)
    self._heading, self._rule, self._feedback, self._minimum = title, rule, feedback, minimum
    self._line = ""
    if numeric:
      self._layout_name = "numbers"  # the digits first; the letters are one key away
    self.set_title(title, rule)

  def line(self) -> Feedback:
    """What the line under the title says now: the rule while nothing is typed, else what the writer's rules say about the text."""
    return self._feedback(self.text) if self.text else Feedback(self._rule)

  def _render(self, rect: rl.Rectangle):
    result = self.line()
    if result.text != self._line:
      self._line = result.text
      self.set_title(self._heading, result.text)
    self._sub_title.set_text_color(ORANGE if result.error else GREY)
    # Only a text the writer would accept can be confirmed; the rest of the check key is the keyboard's own.
    self._min_text_size = self._minimum if self._feedback(self.text).valid else self.NEVER
    return super()._render(rect)


class SidecarLargeView(Widget):
  """Full-screen settings for the X10 sidecar. All state lives in the SidecarFlow; this shows its view and forwards taps."""

  def __init__(self, flow: SidecarControls):
    super().__init__()
    self.flow = flow
    self.view = SidecarView()
    self._words = describe(self.view)
    self._title = UnifiedLabel("X10 sidecar", 76, FontWeight.BOLD, WHITE, wrap_text=False)
    self._status = UnifiedLabel("", 54, FontWeight.MEDIUM, WHITE, wrap_text=False)
    self._detail = UnifiedLabel("", 40, FontWeight.NORMAL, GREY)
    self._warning = UnifiedLabel("", 40, FontWeight.MEDIUM, ORANGE)
    self._labels = [UnifiedLabel(text, 46, FontWeight.MEDIUM, WHITE, wrap_text=False, alignment_vertical=TextAlignmentVertical.MIDDLE)
                    for text in ("Use the X10 for routes", "X10 address", "Port", "Token")]
    self._values = [UnifiedLabel("", 46, FontWeight.NORMAL, GREY, wrap_text=False, alignment_vertical=TextAlignmentVertical.MIDDLE)
                    for _ in range(4)]
    self._switch = self._button("Turn on", self._toggle, locked=True)
    self._address = self._button("Change", self._edit_address, locked=True)
    self._port = self._button("Change", self._edit_port, locked=True)
    self._token = self._button("Change", self._edit_token, locked=True)
    self._reveal = self._button("Show", self._show_token)
    self._reveal.set_enabled(lambda: self.view.editable)  # the token is shown only while parked, like every change
    self._test = self._button("Test connection", self._test_now)
    self._clear = self._button("Clear", self._confirm_clear, style=ButtonStyle.DANGER)
    self._clear.set_enabled(self._can_clear)
    self._close = self._child(Button("Close", gui_app.pop_widget, font_size=48))

  def _can_edit(self) -> bool:
    return self.view.editable and not self.view.busy

  def _can_clear(self) -> bool:
    return self._can_edit() and self.view.can_clear

  def _button(self, text: str, action: Callable[[], None], *, style: ButtonStyle = ButtonStyle.NORMAL, locked: bool = False) -> Button:
    button = self._child(Button(text, action, font_size=42, button_style=style, text_alignment=TextAlignment.CENTER))
    if locked:
      button.set_enabled(self._can_edit)
    return button

  def show_event(self):
    super().show_event()
    self.flow.activate()

  def hide_event(self):
    self.flow.deactivate()
    super().hide_event()

  def _update_state(self):
    self.view = self.flow.update()
    self._words = describe(self.view)
    self._switch.set_text("Turn off" if self.view.enabled else "Turn on")
    self._reveal.set_visible(self.view.token_length > 0)
    notice = self.flow.take_notice()
    if notice:
      gui_app.push_widget(alert_dialog(notice))

  # ------------------------------------------------------------------ actions

  def _toggle(self) -> None:
    self.flow.set_enabled(not self.view.enabled)

  def _edit_address(self) -> None:
    self._keyboard("X10 address", "The X10's IPv4 address, for example 192.168.1.20", host_feedback, self.view.host, minimum=7, maximum=15,
                   numeric=True, apply=self.flow.set_host)

  def _edit_port(self) -> None:
    self._keyboard("Port", "Usually 8765", port_feedback, str(self.view.port), minimum=1, maximum=5, numeric=True, apply=self.flow.set_port)

  def _edit_token(self) -> None:
    self._keyboard("X10 token", f"{config.TOKEN_MIN_LENGTH} to {config.TOKEN_MAX_LENGTH} characters, no spaces. Use the same in the X10 app",
                   token_feedback, "", minimum=config.TOKEN_MIN_LENGTH, maximum=config.TOKEN_MAX_LENGTH, secret=True,
                   apply=self.flow.set_token)

  def _keyboard(self, title: str, rule: str, feedback: Callable[[str], Feedback], current: str, *, minimum: int, maximum: int,
                apply: Callable[[str], bool], secret: bool = False, numeric: bool = False) -> None:
    keyboard = SidecarKeyboard(title, rule, feedback, minimum=minimum, maximum=maximum, secret=secret, numeric=numeric)
    keyboard.set_text(current)

    def entered(result: DialogResult) -> None:
      if result == DialogResult.CONFIRM:
        apply(keyboard.text)  # a refusal comes back as a notice
      keyboard.clear()

    keyboard.set_callback(entered)
    gui_app.push_widget(keyboard)

  def _show_token(self) -> None:
    if not self.view.editable:
      return
    token = self.flow.reveal_token()
    if not token:
      gui_app.push_widget(alert_dialog("No token is saved."))
      return
    step = TOKEN_LINE_GROUPS * 4
    gui_app.push_widget(alert_dialog("Token\n" + "\n".join(group_token(token[index:index + step]) for index in range(0, len(token), step))))

  def _test_now(self) -> None:
    self.flow.test_now()

  def _confirm_clear(self) -> None:
    if not self._can_clear():
      return
    gui_app.push_widget(ConfirmDialog("Turn the X10 sidecar off and remove the saved token?", "Clear", callback=self._cleared))

  def _cleared(self, result: DialogResult) -> None:
    if result == DialogResult.CONFIRM:
      self.flow.clear()

  # ------------------------------------------------------------------ drawing

  def _render(self, rect: rl.Rectangle):
    rl.draw_rectangle_rec(rect, rl.BLACK)
    words = self._words
    x, y = rect.x + LEFT, rect.y
    self._title.render(rl.Rectangle(x, y + 60, 1400, 100))
    self._status.set_text(words.status_title)
    self._status.render(rl.Rectangle(x, y + 165, 1700, 70))
    self._detail.set_text(words.status_detail.replace("\n", ", "))
    self._detail.render(rl.Rectangle(x, y + 240, 1700, 100))
    if words.warning:
      self._warning.set_text(words.warning)
      self._warning.render(rl.Rectangle(x, y + 335, 1700, 60))

    texts = (words.toggle_value, words.address, words.port, words.token)
    buttons = ((self._switch,), (self._address,), (self._port,), (self._reveal, self._token))
    for index, (label, value, text, row) in enumerate(zip(self._labels, self._values, texts, buttons, strict=True)):
      top = y + ROW_TOP + index * ROW_HEIGHT
      label.render(rl.Rectangle(x, top, LABEL_WIDTH, BUTTON_HEIGHT))
      value.set_text(text)
      room = BUTTONS_LEFT - (len(row) - 1) * (BUTTON_WIDTH + BUTTON_GAP) - BUTTON_GAP - VALUE_LEFT  # up to the leftmost button
      value.render(rl.Rectangle(rect.x + VALUE_LEFT, top, room, BUTTON_HEIGHT))
      for position, button in enumerate(reversed(row)):  # the main action is always the rightmost
        button.render(rl.Rectangle(rect.x + BUTTONS_LEFT - position * (BUTTON_WIDTH + BUTTON_GAP), top, BUTTON_WIDTH, BUTTON_HEIGHT))
    bottom = y + ROW_TOP + 4 * ROW_HEIGHT + 20
    self._test.set_text("Checking..." if self.view.checking else f"Test connection: {words.test}")
    self._test.render(rl.Rectangle(x, bottom, 760, BUTTON_HEIGHT))
    self._clear.render(rl.Rectangle(x + 760 + BUTTON_GAP, bottom, BUTTON_WIDTH, BUTTON_HEIGHT))
    self._close.render(rl.Rectangle(rect.x + rect.width - 430, rect.y + rect.height - 165, 300, 110))


class SidecarLarge:
  """The large Settings entry: pushes the page for the session's flow."""

  def __init__(self, flow: SidecarControls):
    self.flow = flow

  def open(self) -> None:
    gui_app.push_widget(SidecarLargeView(self.flow))
