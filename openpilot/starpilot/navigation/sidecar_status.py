"""One bounded GET /status of the X10 route server, turned into a verdict for the comma settings screen.

The question is "does the X10 answer, and is its route engine ready?", asked of the address and port saved in /data/sidecar.json. It is the
same plain, credential-free GET the discovery probe sends (sidecar_config.probe_status): no token, no position, no destination leaves this
device, and nothing is followed (no redirect), decompressed or read beyond MAX_STATUS_BYTES. Every socket operation is limited to 0.7 s and
the whole check to PROBE_DEADLINE_S (1.5 s) however the peer behaves, so a worker thread can run it without a timeout of its own.

/status needs no token, so this cannot tell whether the token matches: that shows only when the X10 refuses a route request. The answer
comes from a device on the LAN and is untrusted; only a few fields are read, and the text shown from them is limited to a fixed alphabet.

fetch_x10_status() and check_x10() never raise.
"""
from __future__ import annotations

import errno
import http.client
import ipaddress
import json
import socket
import time
from enum import StrEnum
from typing import NamedTuple

from openpilot.starpilot.navigation.sidecar_config import (
  MAX_STATUS_BYTES, MIN_API_VERSION, PROBE_DEADLINE_S, PROBE_HEADERS, SERVICE_NAME, STATUS_PATH, DeadlineSocket,
)

MAX_VERSION_CHARS = 24
MAX_STATE_CHARS = 16
VERSION_ALPHABET = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._+-")
STATE_ALPHABET = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")


class Verdict(StrEnum):
  READY = "ready"                  # an openpilot-sidecar answered and its route engine is ready
  WARMING = "warming"              # ... and the engine is starting up
  NO_MAP_DATA = "no_map_data"      # ... and has no route tiles
  ENGINE_ERROR = "engine_error"    # ... and the engine reports an error
  NOT_READY = "not_ready"          # ... and it is not ready for another reason
  UNREACHABLE = "unreachable"      # refused, timed out, no route, connection reset, or any other socket failure
  WRONG_SERVICE = "wrong_service"  # something answered, but it is not an openpilot-sidecar route server
  OWN_ADDRESS = "own_address"      # the address is this device's own: the X10's address belongs here


ANSWERED = frozenset({Verdict.READY, Verdict.WARMING, Verdict.NO_MAP_DATA, Verdict.ENGINE_ERROR, Verdict.NOT_READY})

HEADLINES = {
  Verdict.READY: "X10 ready",
  Verdict.WARMING: "X10 warming up",
  Verdict.NO_MAP_DATA: "X10 has no map data",
  Verdict.ENGINE_ERROR: "X10 engine error",
  Verdict.NOT_READY: "X10 answered, not ready",
  Verdict.UNREACHABLE: "X10 not reachable",
  Verdict.WRONG_SERVICE: "Not the X10 route server",
  Verdict.OWN_ADDRESS: "Comma's own address",
}
SHORT_HEADLINES = {  # for a small card value
  Verdict.READY: "ready",
  Verdict.WARMING: "warming up",
  Verdict.NO_MAP_DATA: "no map data",
  Verdict.ENGINE_ERROR: "engine error",
  Verdict.NOT_READY: "not ready",
  Verdict.UNREACHABLE: "not reachable",
  Verdict.WRONG_SERVICE: "wrong service",
  Verdict.OWN_ADDRESS: "own address",
}
ENGINE_VERDICTS = {"warming": Verdict.WARMING, "noTiles": Verdict.NO_MAP_DATA, "error": Verdict.ENGINE_ERROR}


class X10Status(NamedTuple):
  verdict: Verdict
  host: str
  port: int
  detail: str = ""             # one short line of facts for the screen, built from a fixed alphabet
  api: int | None = None
  version: str = ""
  engine: str = ""
  uptime_s: int | None = None
  http_status: int | None = None
  elapsed_s: float = 0.0

  @property
  def headline(self) -> str:
    return HEADLINES[self.verdict]

  @property
  def short(self) -> str:
    return SHORT_HEADLINES[self.verdict]

  @property
  def answered(self) -> bool:
    """An openpilot-sidecar route server replied (whatever its engine state)."""
    return self.verdict in ANSWERED

  @property
  def ready(self) -> bool:
    return self.verdict is Verdict.READY


def format_uptime(seconds: int) -> str:
  if seconds < 90:
    return f"up {seconds} s"
  if seconds < 90 * 60:
    return f"up {round(seconds / 60)} min"
  if seconds < 48 * 3600:
    return f"up {round(seconds / 3600)} h"
  return f"up {round(seconds / 86400)} d"


def _clean(value: object, alphabet: frozenset[str], limit: int) -> str:
  """`value` if it is a short text of the given alphabet, else "": nothing a peer sends is shown as it came."""
  if type(value) is str and 0 < len(value) <= limit and all(char in alphabet for char in value):
    return value
  return ""


def _non_negative_int(value: object) -> int | None:
  return value if type(value) is int and 0 <= value < 10**9 else None


def classify_status(host: str, port: int, http_status: int, body: bytes, elapsed_s: float = 0.0) -> X10Status:
  """The verdict for one HTTP answer: `body` is at most MAX_STATUS_BYTES + 1 bytes of it."""
  def wrong(detail: str) -> X10Status:
    return X10Status(Verdict.WRONG_SERVICE, host, port, detail, http_status=http_status, elapsed_s=elapsed_s)

  if http_status != 200:
    return wrong(f"HTTP {http_status}" + (": access refused" if http_status == 403 else ""))
  if len(body) > MAX_STATUS_BYTES:
    return wrong("answer is too large")
  try:
    document = json.loads(body.decode("utf-8"))
  except (ValueError, RecursionError):
    return wrong("answer is not JSON")
  if not isinstance(document, dict) or document.get("service") != SERVICE_NAME:
    return wrong("not an openpilot-sidecar")
  api = document.get("api")
  if type(api) is not int or api < MIN_API_VERSION:
    return wrong("unknown API version")

  engine_document = document.get("engine")
  state_text = engine_document.get("state") if isinstance(engine_document, dict) else None
  state = _clean(state_text, STATE_ALPHABET, MAX_STATE_CHARS)
  ready = document.get("ready")
  note = ""
  if ready is True or (ready is None and state == "ready"):
    verdict = Verdict.READY
  elif state == "ready":  # the engine is open, but its test route failed
    verdict, note = Verdict.NOT_READY, "test route failed"
  else:
    verdict = ENGINE_VERDICTS.get(state, Verdict.NOT_READY)
    note = f"engine {state}" if verdict is Verdict.NOT_READY and state else ""
  version = _clean(document.get("version"), VERSION_ALPHABET, MAX_VERSION_CHARS)
  uptime = _non_negative_int(document.get("uptimeSec"))
  facts = [f"api {api}"] if api != MIN_API_VERSION else []
  if version:
    facts.append(f"app {version}")
  if note:
    facts.append(note)
  if uptime is not None:
    facts.append(format_uptime(uptime))
  return X10Status(verdict, host, port, ", ".join(facts), api=api, version=version, engine=state, uptime_s=uptime,
                   http_status=200, elapsed_s=elapsed_s)


def fetch_x10_status(host: str, port: int, *, clock=time.monotonic) -> X10Status:
  """GET http://host:port/status within PROBE_DEADLINE_S and classify the answer. Never raises."""
  started = clock()

  def failed(verdict: Verdict, detail: str) -> X10Status:
    return X10Status(verdict, host, port, detail, elapsed_s=clock() - started)

  connection = None
  try:
    connection = http.client.HTTPConnection(host, port)
    connection.sock = DeadlineSocket(started + PROBE_DEADLINE_S)  # request() skips connect() when a socket is set
    connection.sock.connect((host, port))
    connection.request("GET", STATUS_PATH, headers=PROBE_HEADERS)
    response = connection.getresponse()
    body = response.read(MAX_STATUS_BYTES + 1)
    return classify_status(host, port, response.status, body, clock() - started)
  except TimeoutError:
    return failed(Verdict.UNREACHABLE, "no answer in time")
  except ConnectionRefusedError:
    return failed(Verdict.UNREACHABLE, "connection refused")
  except http.client.HTTPException:  # includes a peer that closes without answering (RemoteDisconnected)
    return failed(Verdict.WRONG_SERVICE, "not an HTTP server")
  except OSError as error:
    return failed(Verdict.UNREACHABLE, "no route to that address" if error.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH)
                  else "connection failed")
  except Exception:  # an out-of-range port or similar: the screen validates first, but this must not escape a worker thread
    return failed(Verdict.UNREACHABLE, "connection failed")
  finally:
    if connection is not None:
      connection.close()


def is_own_address(host: str) -> bool:
  """True if `host` is an address of this device (loopback included). Sends nothing: a UDP connect only picks a source address."""
  try:
    if ipaddress.ip_address(host).is_loopback:  # 127/8 is all this device, though the kernel picks 127.0.0.1 as the source
      return True
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
      probe.connect((host, 9))
      return probe.getsockname()[0] == host
  except (OSError, OverflowError, ValueError):
    return False


def check_x10(host: str, port: int) -> X10Status:
  """The status line of the settings screen: the comma's own address is caught before anything is sent. Never raises."""
  if is_own_address(host):
    return X10Status(Verdict.OWN_ADDRESS, host, port, "Enter the X10's address instead.")
  return fetch_x10_status(host, port)
