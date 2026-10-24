"""Controller and texts of the X10 sidecar settings screen, shared by the compact (C4) and large (C3) pages.

The screen edits /data/sidecar.json through sidecar_config.save_config() and shows whether the X10 answers. Nothing here draws or imports
pyray, so every rule is testable without a window.

Threads: the screen calls update() once per frame on the UI thread and never waits. File writes (they flush to disk) and the GET /status
check (up to 1.5 s) run on a small worker pool and hand their result back through a Future that update() collects; the workers share no state
with the flow. The saved file is re-read twice a second, so a change made over SSH shows up too.

Rules: an edit is allowed only while parked (the `allowed` callable) and one at a time. Text is checked on the UI thread with the same
functions the writer uses, so the screen explains a refusal before anything is written. The token is never part of a view: it can be
replaced and its length shown, and reveal_token() reads it only when the user asks. A saved change reaches navigationd and Galaxy on
their next read of the file (about a second); no restart is needed.
"""
from __future__ import annotations

import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from functools import partial
from typing import Any, Protocol

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.sidecar_status import X10Status, check_x10

POLL_INTERVAL_S = 5.0   # while the screen is open
READ_INTERVAL_S = 0.5   # how often the saved file is looked at
LOCKED_NOTICE = "Park the car to change the X10 settings"
BUSY_NOTICE = "Saving, wait a moment"
DEFAULT_NOTICE_PORT = "The port must be a number from 1 to 65535"
HIDDEN_CHAR = "•"


@dataclass(frozen=True)
class SidecarView:
  """One immutable picture of the screen's data. It never holds the token, only whether and how long it is."""
  document: str = config.DOC_MISSING      # config.DOC_* state of the settings file
  enabled: bool = False
  host: str = ""                          # the saved fixed LAN address, "" when there is none
  host_note: str = ""                     # why there is none: "auto" (auto discovery) or "invalid"; "" when unset or fine
  port: int = config.DEFAULT_PORT
  token_length: int = 0                   # 0: no token saved
  token_problem: str = ""                 # why the saved token is unusable, "" when fine or absent
  editable: bool = True                   # the car is parked
  busy: bool = False                      # a change is being written
  checking: bool = False                  # a status check is running
  status: X10Status | None = None         # the last check of the saved address and port
  status_age_s: float | None = None

  @property
  def readable(self) -> bool:
    return self.document in (config.DOC_MISSING, config.DOC_OK)

  @property
  def has_host(self) -> bool:
    return bool(self.host)

  @property
  def has_token(self) -> bool:
    return self.token_length > 0 and not self.token_problem

  @property
  def can_enable(self) -> bool:
    return self.readable and self.has_host and self.has_token


@dataclass(frozen=True)
class SidecarText:
  """The words of the screen, in sentence case; the compact page lowercases them like its siblings."""
  status_title: str
  status_detail: str
  toggle_value: str
  address: str
  port: str
  token: str
  test: str
  warning: str = ""


def age_text(seconds: float) -> str:
  seconds = max(0, int(seconds))
  if seconds < 3:
    return "just now"
  if seconds < 90:
    return f"{seconds} s ago"
  if seconds < 90 * 60:
    return f"{round(seconds / 60)} min ago"
  return f"{round(seconds / 3600)} h ago"


def group_token(token: str, size: int = 4) -> str:
  """The token in groups of `size`, so that it can be compared with the X10 app by eye. Display only."""
  return " ".join(token[index:index + size] for index in range(0, len(token), size))


def hide_text(text: str) -> str:
  return HIDDEN_CHAR * len(text)


def describe(view: SidecarView) -> SidecarText:
  """What each card says for this view."""
  address = view.host or {"auto": "auto discovery", "invalid": "not valid"}.get(view.host_note, "not set")
  if not view.token_length:
    token = "not set"
  elif view.token_problem:
    token = "needs fixing"
  else:
    token = f"set, {view.token_length} characters"
  port = f"{view.port} (default)" if view.port == config.DEFAULT_PORT else str(view.port)

  if view.enabled and view.can_enable:
    toggle = "routes via the X10"
  elif view.enabled:
    toggle = "fix address, token"
  elif view.can_enable:
    toggle = "off"
  else:
    toggle = "set up first"

  warning = ""
  if not view.editable:
    warning = "Park the car to change the X10 settings."
  elif view.token_length and view.token_problem:
    warning = view.token_problem + "."

  if view.document == config.DOC_CORRUPT:
    title, detail = "Settings file damaged", "Clear it to start again. A backup is kept."
  elif view.document == config.DOC_UNREADABLE:
    title, detail = "Settings file unreadable", "The comma cannot read it."
  elif not view.has_host:
    title = "X10 address not valid" if view.host_note == "invalid" else "X10 address not set"
    detail = ("Auto discovery is in use. Enter an address to send destinations." if view.enabled and view.host_note == "auto"
              else "Enter the X10's IPv4 address.")
  elif view.status is None:
    title = "Checking the X10" if view.checking else "X10 not checked yet"
    detail = f"{view.host}:{view.port}"
  else:
    title = view.status.headline
    detail = view.status.detail + (f"\nchecked {age_text(view.status_age_s)}" if view.status_age_s is not None else "")

  if view.checking:
    test = "checking" if view.status is None else "checking again"
  elif view.status is not None:
    test = view.status.short
  else:
    test = "tap to check" if view.has_host else "set address first"
  return SidecarText(title, detail, toggle, address, port, token, test, warning)


@dataclass(frozen=True)
class _Settings:
  document: str
  enabled: bool
  host: str
  host_note: str
  port: int
  token_length: int
  token_problem: str


def _settings_of(document: config.ConfigDocument) -> _Settings:
  values = document.config
  host = values.get("host")
  if type(host) is str and config.is_lan_address(host):
    fixed, note = host, ""
  elif host is None or (type(host) is str and host.strip().lower() == config.HOST_AUTO):
    fixed, note = "", "auto" if document.state == config.DOC_OK else ""
  else:
    fixed, note = "", "invalid"
  token = values.get("token")
  present = "token" in values and token != ""  # an empty text is "no token" to the user
  length = len(token) if type(token) is str else (1 if present else 0)
  problem = config.token_problem(token) if present else None
  return _Settings(document.state, values.get("enabled") is True, fixed, note, config.port_of(values), length, problem or "")


class SidecarControls(Protocol):
  """What the screens use of a flow: a real SidecarFlow, or a stand-in for previews and tests."""

  def activate(self) -> None: ...
  def deactivate(self) -> None: ...
  def update(self) -> SidecarView: ...
  def take_notice(self) -> str: ...
  def reveal_token(self) -> str: ...
  def test_now(self) -> bool: ...
  def set_enabled(self, enabled: bool) -> bool: ...
  def set_host(self, text: str) -> bool: ...
  def set_port(self, text: str) -> bool: ...
  def set_token(self, text: str) -> bool: ...
  def clear(self) -> bool: ...


class SidecarFlow:
  """Reads the saved settings, applies edits off the UI thread and polls the X10; the screens only call update() and the request methods."""

  def __init__(self, allowed: Callable[[], bool], *, path: str | None = None, check: Callable[[str, int], X10Status] = check_x10,
               now: Callable[[], float] = time.monotonic, executor: Any = None):
    self._allowed = allowed
    self._path = path
    self._check = check
    self._now = now
    self._executor = executor
    self._owns_executor = executor is None
    self._active = False
    self._settings = _Settings(config.DOC_MISSING, False, "", "", config.DEFAULT_PORT, 0, "")
    self._read_at: float | None = None
    self._save: Future | None = None
    self._probe: Future | None = None
    self._probe_target: tuple[str, int] | None = None
    self._last_target: tuple[str, int] | None = None
    self._next_probe = 0.0
    self._status: X10Status | None = None
    self._status_at = 0.0
    self._notice = ""
    self._closed = False

  # ------------------------------------------------------------------ lifecycle

  def activate(self) -> None:
    """The screen is shown: re-read the file and check the X10 right away."""
    self._active = True
    self._read_at = None
    self._next_probe = 0.0

  def deactivate(self) -> None:
    self._active = False

  def close(self) -> None:
    self._closed = True
    self._active = False
    if self._executor is not None and self._owns_executor:
      self._executor.shutdown(wait=False, cancel_futures=True)
    self._executor = None

  # ------------------------------------------------------------------ one frame

  def update(self) -> SidecarView:
    """Collect finished work, schedule the next check and return the view. Never blocks."""
    now = self._now()
    self._collect_save()
    if self._read_at is None or now - self._read_at >= READ_INTERVAL_S:
      self._settings = _settings_of(config.read_config_document(self._path))
      self._read_at = now
    self._collect_probe(now)
    settings = self._settings
    target = (settings.host, settings.port)
    if target != self._last_target:  # edited here or over SSH: ask the new address at once, not at the next interval
      self._last_target = target
      self._next_probe = 0.0
    if self._status is not None and (self._status.host, self._status.port) != target:
      self._status = None  # the old answer says nothing about the new address or port
    if self._active and not self._closed and settings.host and self._probe is None and now >= self._next_probe:
      self._probe_target = target
      self._probe = self._submit(self._check, settings.host, settings.port)
      self._next_probe = now + POLL_INTERVAL_S
    return SidecarView(
      document=settings.document, enabled=settings.enabled, host=settings.host, host_note=settings.host_note, port=settings.port,
      token_length=settings.token_length, token_problem=settings.token_problem, editable=bool(self._allowed()),
      busy=self._save is not None, checking=self._probe is not None, status=self._status,
      status_age_s=(now - self._status_at) if self._status is not None else None)

  def _submit(self, function: Callable[..., Any], *args: Any) -> Future | None:
    if self._closed:
      return None
    if self._executor is None:
      self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="sidecar-ui")
    return self._executor.submit(function, *args)

  def _collect_save(self) -> None:
    future = self._save
    if future is None or not future.done():
      return
    self._save = None
    try:
      result = future.result()
    except Exception:  # save_config reports expected failures itself; this is a bug, say so without details
      self._notice = "Could not save the X10 settings"
      return
    if result.code == config.SAVE_UNCHANGED:
      return
    if not result.ok:
      self._notice = result.message
      return
    self._read_at = None  # read the new file now
    self._next_probe = 0.0  # and ask the X10 again

  def _collect_probe(self, now: float) -> None:
    future = self._probe
    if future is None or not future.done():
      return
    self._probe = None
    try:
      status = future.result()
    except Exception:  # check_x10 never raises; a replaced one still must not break the screen
      status = None
    if status is None:
      return
    if self._probe_target != (self._settings.host, self._settings.port):
      self._next_probe = 0.0  # the settings changed while it ran: ask again for the new ones
      return
    self._status, self._status_at = status, now

  # ------------------------------------------------------------------ requests (UI thread)

  def take_notice(self) -> str:
    """The reason the last edit did not happen, once; the screen shows it as a message."""
    notice, self._notice = self._notice, ""
    return notice

  def _change(self, updates: dict[str, object], remove: tuple[str, ...] = (), *, replace_corrupt: bool = False) -> bool:
    if not self._allowed():
      self._notice = LOCKED_NOTICE
      return False
    if self._save is not None:
      self._notice = BUSY_NOTICE
      return False
    future = self._submit(partial(config.save_config, updates, remove=remove, path=self._path, replace_corrupt=replace_corrupt))
    self._save = future
    return future is not None

  def set_enabled(self, enabled: bool) -> bool:
    return self._change({"enabled": bool(enabled)})

  def set_host(self, text: str) -> bool:
    problem = config.host_problem(text)
    if problem is not None:
      self._notice = problem
      return False
    return self._change({"host": config.normalize_host(text)})

  def set_port(self, text: str) -> bool:
    port = config.parse_port(text)
    if port is None:
      self._notice = DEFAULT_NOTICE_PORT
      return False
    return self._change({"port": port})

  def set_token(self, text: str) -> bool:
    problem = config.token_problem(text)
    if problem is not None:
      self._notice = problem
      return False
    return self._change({"token": text})

  def clear(self) -> bool:
    """Turn the sidecar off and forget the token; the address and port stay. A damaged file is replaced after a backup."""
    return self._change({"enabled": False}, ("token",), replace_corrupt=True)

  def test_now(self) -> bool:
    """Ask the X10 again as soon as possible; False when there is no address to ask."""
    if not self._settings.host:
      self._notice = "Set the X10 address first"
      return False
    self._next_probe = 0.0
    return True

  def reveal_token(self) -> str:
    """The saved token as printable ASCII, for the user's explicit 'show token'; "" when there is none."""
    token = config.read_config_document(self._path).config.get("token")
    if type(token) is not str:
      return ""
    return "".join(char if "!" <= char <= "~" else "?" for char in token[:config.TOKEN_MAX_LENGTH])
