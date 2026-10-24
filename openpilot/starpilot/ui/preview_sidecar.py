"""Development-only capture of the X10 sidecar settings pages with supplied sample state.

This entry point never reads or writes /data/sidecar.json, never contacts an X10 and starts no device service: the pages are given fixed
SidecarView samples through a stand-in flow. Images are evidence under an explicit output directory, not runtime assets. It needs a
graphics context; without a display use the headless backend:

  RAYLIB_BACKEND=headless ./dev python -m openpilot.starpilot.ui.preview_sidecar --output /tmp/sidecar-preview
  RAYLIB_BACKEND=headless ./dev python -m openpilot.starpilot.ui.preview_sidecar --profile large --output /tmp/sidecar-preview-large

The compact profile captures every card of the page (it scrolls sideways) and the keyboard dialogs with typical input; the large profile
captures the whole page and its keyboard.
"""

import argparse
import os
from collections.abc import Callable
from dataclasses import replace
from pathlib import Path
from typing import Any

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.sidecar_status import Verdict, X10Status
from openpilot.starpilot.ui.sidecar_flow import SidecarView

SAMPLE_TOKEN = "0123456789abcdef0123456789abcdef"  # an obviously fake value; the preview never touches a real one
HOST = "192.168.1.20"
READY = X10Status(Verdict.READY, HOST, 8765, "app 1.4.2, up 2 h", api=1, version="1.4.2", engine="ready", uptime_s=7200, http_status=200)

CONFIGURED = SidecarView(document=config.DOC_OK, enabled=True, host=HOST, port=8765, token_length=len(SAMPLE_TOKEN), status=READY,
                         status_age_s=3.0)
VIEWS = {
  "ready": CONFIGURED,
  "off": replace(CONFIGURED, enabled=False),
  "unreachable": replace(CONFIGURED, status=X10Status(Verdict.UNREACHABLE, HOST, 8765, "connection refused"), status_age_s=6.0),
  "wrong_service": replace(CONFIGURED, status=X10Status(Verdict.WRONG_SERVICE, HOST, 8765, "HTTP 404", http_status=404), status_age_s=1.0),
  "warming": replace(CONFIGURED, status=X10Status(Verdict.WARMING, HOST, 8765, "app 1.4.2, up 12 s", api=1, version="1.4.2"),
                     status_age_s=2.0),
  "checking": replace(CONFIGURED, status=None, status_age_s=None, checking=True),
  "fresh": SidecarView(document=config.DOC_MISSING),
  "damaged": SidecarView(document=config.DOC_CORRUPT),
  "short_token": replace(CONFIGURED, token_length=5, token_problem="The token needs at least 10 characters (now 5)"),
  "auto_host": SidecarView(document=config.DOC_OK, enabled=True, host_note="auto", token_length=24, port=8765),
  "driving": replace(CONFIGURED, editable=False),
}
Scene = tuple[str, Callable[[], Any], str | None]  # name, widget factory, "cards" to capture every card of a scrolling page
TOKEN_LINES = "\n".join(" ".join(SAMPLE_TOKEN[i:i + 4] for i in range(j, min(j + 16, len(SAMPLE_TOKEN)), 4)) for j in range(0, len(SAMPLE_TOKEN), 16))


class PreviewFlow:
  """A stand-in for SidecarFlow that always answers with the sample view."""

  def __init__(self, view: SidecarView):
    self.view = view

  def activate(self) -> None:
    pass

  def deactivate(self) -> None:
    pass

  def update(self) -> SidecarView:
    return self.view

  def take_notice(self) -> str:
    return ""

  def reveal_token(self) -> str:
    return SAMPLE_TOKEN

  def test_now(self) -> bool:
    return True

  def set_enabled(self, enabled: bool) -> bool:
    return False

  def set_host(self, text: str) -> bool:
    return False

  def set_port(self, text: str) -> bool:
    return False

  def set_token(self, text: str) -> bool:
    return False

  def clear(self) -> bool:
    return False


def compact_scenes(views: list[str]) -> list[Scene]:
  from openpilot.selfdrive.ui.mici.widgets.dialog import SettingDescriptionDialog
  from openpilot.starpilot.ui.sidecar_compact import SidecarInputDialog, SidecarPage, host_feedback, port_feedback, token_feedback

  def dialog(hint, text, feedback, *, numeric=False, secret=False, reveal=False):
    def build():
      widget = SidecarInputDialog(hint, "", feedback=feedback, confirm_callback=lambda _text: None, numeric=numeric, secret=secret)
      widget._keyboard.set_text(text)
      if reveal:
        widget.toggle_reveal()
      return widget
    return build

  scenes: list[Scene] = [(f"page_{name}", lambda view=VIEWS[name]: SidecarPage(PreviewFlow(view)), "cards") for name in views]
  return scenes + [
    ("dialog_address_empty", dialog("x10 address", "", host_feedback, numeric=True), None),
    ("dialog_address_typing", dialog("x10 address", "192.168.1", host_feedback, numeric=True), None),
    ("dialog_address_public", dialog("x10 address", "8.8.8.8", host_feedback, numeric=True), None),
    ("dialog_address_good", dialog("x10 address", HOST, host_feedback, numeric=True), None),
    ("dialog_port_bad", dialog("port", "70000", port_feedback, numeric=True), None),
    ("dialog_token_empty", dialog("token (10-128)", "", token_feedback, secret=True), None),
    ("dialog_token_short", dialog("token (10-128)", "abc123", token_feedback, secret=True), None),
    ("dialog_token_masked", dialog("token (10-128)", SAMPLE_TOKEN, token_feedback, secret=True), None),
    ("dialog_token_shown", dialog("token (10-128)", SAMPLE_TOKEN, token_feedback, secret=True, reveal=True), None),
    ("dialog_token_space", dialog("token (10-128)", "has space in it", token_feedback, secret=True, reveal=True), None),
    ("show_token", lambda: SettingDescriptionDialog("token", TOKEN_LINES), "cards"),
  ]


def large_scenes(views: list[str]) -> list[Scene]:
  from openpilot.starpilot.ui.sidecar_large import SidecarLargeView
  from openpilot.system.ui.widgets.confirm_dialog import ConfirmDialog, alert_dialog
  from openpilot.system.ui.widgets.keyboard import Keyboard

  def keyboard(title, subtitle, text, *, minimum, maximum, secret=False):
    def build():
      widget = Keyboard(max_text_size=maximum, min_text_size=minimum, password_mode=secret, show_password_toggle=secret)
      widget.set_title(title, subtitle)
      widget.set_text(text)
      return widget
    return build

  scenes: list[Scene] = [(f"page_{name}", lambda view=VIEWS[name]: SidecarLargeView(PreviewFlow(view)), None) for name in views]
  return scenes + [
    ("keyboard_address", keyboard("X10 address", "The X10's IPv4 address, for example 192.168.1.20", HOST, minimum=7, maximum=15), None),
    ("keyboard_token", keyboard("X10 token", "10 to 128 characters, no spaces. Use the same in the X10 app", SAMPLE_TOKEN, minimum=10,
                                maximum=128, secret=True), None),
    ("notice", lambda: alert_dialog("The token needs at least 10 characters (now 5)"), None),
    ("show_token", lambda: alert_dialog("Token\n" + TOKEN_LINES), None),
    ("confirm_clear", lambda: ConfirmDialog("Turn the X10 sidecar off and remove the saved token?", "Clear"), None),
  ]


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument("--output", type=Path, required=True)
  parser.add_argument("--profile", choices=("compact", "large"), default="compact")
  parser.add_argument("--views", nargs="*", choices=sorted(VIEWS), default=["ready", "unreachable", "fresh", "damaged", "driving"])
  args = parser.parse_args()
  if args.output.exists():
    raise ValueError("Refusing to overwrite existing preview evidence")
  args.output.mkdir(parents=True)
  os.environ.setdefault("OFFSCREEN", "1")  # these are read when the UI application module is imported
  os.environ.setdefault("SCALE", "1")
  if args.profile == "large":
    os.environ["BIG"] = "1"

  import pyray as rl

  from openpilot.system.ui.lib.application import gui_app
  from openpilot.system.ui.widgets import Widget

  gui_app.init_window("X10 sidecar preview", fps=60)
  if not rl.is_window_ready():
    raise RuntimeError("Unable to initialize native graphics")

  class Base(Widget):  # the bottom of the navigation stack: the scenes are pushed and popped above it
    def _render(self, _):
      pass

  gui_app.push_widget(Base())
  frame = [0]
  rl.get_frame_time = lambda: 1.0 / 60  # deterministic animation: the frame count is the clock
  rl.get_time = lambda: frame[0] / 60

  scenes = compact_scenes(args.views) if args.profile == "compact" else large_scenes(args.views)

  def capture(path: Path) -> None:
    rl.rl_draw_render_batch_active()  # raylib batches draw calls: flush them before reading the screen
    image = rl.load_image_from_screen()
    try:
      if not rl.export_image(image, str(path)):
        raise RuntimeError(f"Unable to export {path.name}")
    finally:
      rl.unload_image(image)

  settle = 70  # frames for the slide-in animation of a NavWidget
  index, started, current, starts, card = 0, 0, None, [], 0
  for should_render, _frame_time, _cpu_time in gui_app.render():
    if not should_render:
      continue
    frame[0] += 1
    if current is None:
      if index >= len(scenes):
        gui_app.request_close()
        continue
      current = scenes[index][1]()
      gui_app.push_widget(current)
      started, starts, card = frame[0], [], 0
      continue
    name, _factory, mode = scenes[index]
    elapsed = frame[0] - started
    if elapsed < settle:
      continue
    if mode == "cards":
      if not starts:
        starts = [item.rect.x for item in current._scroller.items if item.is_visible]
      current._scroller.scroll_panel.set_offset(-(starts[card] - 20))
      if elapsed >= settle + 6 * (card + 1):
        capture(args.output / f"{name}_{card + 1:02d}.png")
        card += 1
      if card < len(starts):
        continue
    else:
      capture(args.output / f"{name}.png")
    gui_app.pop_widget()
    current = None
    index += 1


if __name__ == "__main__":
  main()
