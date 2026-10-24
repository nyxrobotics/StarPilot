"""Opt-in LAN route-server configuration and bounded discovery for StarPilot navigation.

Sidecar mode sends RouteRuntime's route requests to a route server on the local network (the Android head
unit) instead of api.mapbox.com. The configuration is also read by the scoped Galaxy X10 bridge.

Rules, in order of importance:
  * OFF unless the JSON file (default /data/sidecar.json) exists, parses, and says {"enabled": true}.
    Anything else (missing file, bad JSON, wrong types) means OFF, i.e. stock StarPilot behavior.
  * No guessing: an unusable address yields None and the caller fails closed (no route, no desire).
  * Standard library only: RouteRuntime, NavigationOwner and Galaxy import it, so these getters must stay cheap.
    Only resolve_route_server() with "host": "auto" touches the network, and only from RouteRuntime's fetch worker
    thread; sidecar_enabled() and the other getters never do.

Config file keys (all optional except "enabled"):
  {"enabled": true, "port": 8765, "token": "sidecar", "interface": "wlan0", "host": "auto"}
  host       "auto" (the default when the key is absent) or the fixed IPv4 address of the route server
             (RFC 1918, loopback 127/8 or link-local 169.254/16 only, see is_lan_address). A fixed address skips
             discovery. Any other value is unusable and means "no route" (fail closed), it never falls back to discovery.
  interface  Wi-Fi interface whose default gateway and ARP neighbors are used (default wlan0)
  port       TCP port of the route server (default 8765)
  token      sent as access_token; the route server may require it as a shared secret (default "sidecar")

Token rules: the route request reader below is lenient (any printable ASCII token of 1..128 characters is sent, the route server decides
what it accepts, and a missing key means the default). Where this device authenticates or writes a secret, the strength rule applies:
TOKEN_MIN_LENGTH..TOKEN_MAX_LENGTH (10..128) printable ASCII characters, no spaces (token_problem). That is the Galaxy X10 bridge
(destination commands from the X10) and the settings screen's writer (save_config).

"host": "auto" (DESIGN section 2: the app must work in all three topologies, the patch assumes none):
  A  the head unit is the hotspot           -> it is the default gateway of `interface`
  B  the comma is the hotspot (head unit    -> `interface` has no default gateway; the head unit is one of its
     is a Wi-Fi client)                        ARP neighbors (/proc/net/arp)
  C  both are clients of one access point   -> the gateway is the router; the head unit is an ARP neighbor
                                               (or set "host" to its address)
Candidates, in priority order: the default gateway of `interface`, then the ARP neighbors of `interface`
(complete entries on that interface only, RFC 1918 IPv4 only, no duplicates), at most 6 in total. Every
candidate is probed in parallel with GET http://candidate:port/status (a plain GET: no token, no position, nothing
of the destination is sent to a host that has not been verified). A candidate verifies when it answers
HTTP 200 with a JSON object of at most 8 KiB, {"service": "openpilot-sidecar", "api": N >= 1}. The first
candidate in priority order that verifies wins. Time is bounded: 0.7 s per socket operation, 1.5 s per probe in
total (a server that trickles bytes cannot stretch it), all probes run side by side.
The verified host is cached for about 20 s (monotonic clock) and dropped on any failed route fetch
(invalidate_discovery). If nothing verifies, the unverified default gateway is used exactly as before (the head
unit is often the gateway and its app may simply not be ready yet); without a gateway there is no route.
While there is no route, RouteRuntime retries after 30 seconds, so a lookup that finds nothing can repeat the probes
(at most 6 short TCP connections per retry, nothing else is sent). The ARP table only lists hosts this device has
exchanged packets with: the head unit shows up once it has talked to the comma (sending a destination does that).
"/status" being answered is NOT authentication: any device that can join the Wi-Fi can claim to be the route
server, exactly as it could by becoming the gateway. Use a strong hotspot password (see starpilot/README.md).
"""
from __future__ import annotations

import ipaddress
import json
import os
import socket
import struct
import threading
import time
from typing import NamedTuple, TypeGuard

CONFIG_PATH = "/data/sidecar.json"
CONFIG_ENV = "SIDECAR_CONFIG"
ROUTE_TABLE_PATH = "/proc/net/route"
ARP_TABLE_PATH = "/proc/net/arp"

DEFAULT_PORT = 8765
DEFAULT_INTERFACE = "wlan0"
DEFAULT_TOKEN = "sidecar"
TOKEN_MIN_LENGTH = 10   # shortest token the Galaxy X10 bridge accepts and the settings screen writes (token_problem)
TOKEN_MAX_LENGTH = 128
DIRECTIONS_PATH = "/directions/v5/mapbox/driving-traffic"

HOST_AUTO = "auto"
SOURCE_FIXED = "fixed"        # "host" is an address in the config file
SOURCE_VERIFIED = "verified"  # answered GET /status as an openpilot-sidecar
SOURCE_GATEWAY = "gateway"    # nothing verified; the default gateway, unverified (the pre-discovery behavior)

STATUS_PATH = "/status"
SERVICE_NAME = "openpilot-sidecar"  # discovery signature of GET /status (DESIGN 4.4)
MIN_API_VERSION = 1
MAX_DISCOVERY_CANDIDATES = 6
PROBE_TIMEOUT_S = 0.7        # connect, and every single send/receive
PROBE_DEADLINE_S = 1.5       # one probe in total, whatever the peer does
PROBE_JOIN_SLACK_S = 0.25    # extra time the coordinator waits for the probe threads before giving up on them
MAX_STATUS_BYTES = 8192      # a real answer is ~200 bytes
DISCOVERY_CACHE_TTL_S = 20.0

MAX_CONFIG_BYTES = 65536
MAX_ARP_BYTES = 262144
MAX_ROUTE_TABLE_BYTES = 262144
RTF_UP = 0x1
RTF_GATEWAY = 0x2
ATF_COM = 0x2  # ARP entry is resolved ("complete")
UNSET_HW_ADDRESS = "00:00:00:00:00:00"
BROADCAST_HW_ADDRESS = "ff:ff:ff:ff:ff:ff"
# A default route over one of these is a mobile-network path, never the head unit.
CELLULAR_INTERFACE_PREFIXES = ("wwan", "ppp", "rmnet", "ccmni")
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(network) for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
LOOPBACK_AND_LINK_LOCAL_NETWORKS = tuple(ipaddress.ip_network(network) for network in ("127.0.0.0/8", "169.254.0.0/16"))
PROBE_HEADERS = {"Accept": "application/json", "Connection": "close", "User-Agent": "starpilot-sidecar-discovery/2"}

# (stat signature, parsed config); replaced as one tuple so concurrent readers never see a mixed pair
_cache: tuple[object, dict] = (None, {})

_monotonic = time.monotonic  # the clock of the discovery cache; tests replace it


class RouteServer(NamedTuple):
  """Where route requests go right now, and how that address was found."""
  host: str
  port: int
  source: str  # SOURCE_FIXED | SOURCE_VERIFIED | SOURCE_GATEWAY
  probed: int = 0  # how many candidates were probed for this answer (0: fixed host or cache hit)

  @property
  def directions_url(self) -> str:
    return f"http://{self.host}:{self.port}{DIRECTIONS_PATH}"


class _Verified(NamedTuple):
  interface: str
  port: int
  host: str
  expires_at: float


_discovery_lock = threading.Lock()
_verified: _Verified | None = None
_discovery_generation = 0  # bumped by invalidate_discovery(); a probe round started before that must not repopulate the cache


def config_path() -> str:
  return os.environ.get(CONFIG_ENV) or CONFIG_PATH


def load_config() -> dict:
  """Parsed config, or {} for a missing/invalid file. Re-read only when the file changes."""
  global _cache
  path = config_path()
  try:
    st = os.stat(path)
  except OSError:
    _cache = (None, {})
    return {}

  if st.st_size > MAX_CONFIG_BYTES:
    _cache = (None, {})
    return {}

  signature = (path, st.st_mtime_ns, st.st_size, st.st_ino)
  if _cache[0] == signature:
    return _cache[1]

  config: dict = {}
  try:
    with open(path, "rb") as f:
      raw = f.read(MAX_CONFIG_BYTES + 1)
    if len(raw) > MAX_CONFIG_BYTES:  # the file may have grown since stat()
      raise ValueError("sidecar config is too large")
    parsed = json.loads(raw.decode("utf-8"))
    if isinstance(parsed, dict):
      config = parsed
  except (OSError, ValueError, RecursionError):  # a file of nested brackets is "not a config", not a crash of navigationd / Galaxy
    config = {}
  _cache = (signature, config)
  return config


def sidecar_enabled() -> bool:
  return load_config().get("enabled") is True


def sidecar_port() -> int:
  port = load_config().get("port", DEFAULT_PORT)
  if isinstance(port, str) and port.isascii() and port.isdigit():  # str.isdigit() alone also accepts "²" (int() raises) and "٣" (= port 3)
    try:
      port = int(port)
    except ValueError:  # more digits than int() accepts
      return DEFAULT_PORT
  if isinstance(port, int) and not isinstance(port, bool) and 0 < port < 65536:
    return port
  return DEFAULT_PORT


def _is_printable_ascii(text: str) -> bool:
  """Only 0x21-0x7e: no space, no control character (including DEL), nothing beyond ASCII."""
  return text.isascii() and all("!" <= ch <= "~" for ch in text)


def token_problem(token: object) -> str | None:
  """Why `token` is not acceptable as the shared X10 token, or None when it is.

  One rule for every place that checks token strength (the Galaxy X10 bridge and the settings screen's writer): a str of
  TOKEN_MIN_LENGTH..TOKEN_MAX_LENGTH printable ASCII characters. The message names the rule and the length, never the token.
  """
  if type(token) is not str:
    return "The token must be text"
  if not _is_printable_ascii(token):
    return "The token may only use printable ASCII characters, without spaces"
  if len(token) < TOKEN_MIN_LENGTH:
    return f"The token needs at least {TOKEN_MIN_LENGTH} characters (now {len(token)})"
  if len(token) > TOKEN_MAX_LENGTH:
    return f"The token may have at most {TOKEN_MAX_LENGTH} characters (now {len(token)})"
  return None


def is_valid_token(token: object) -> TypeGuard[str]:
  return token_problem(token) is None


def sidecar_token() -> str:
  """Route API access_token; an explicitly invalid value disables route fetches.

  Lenient on purpose (see the module docstring): this device only sends the token, so a short legacy value (and the default for a
  missing key) keeps working. The strength rule, is_valid_token(), guards what this device accepts and writes.
  """
  config = load_config()
  if "token" not in config:
    return DEFAULT_TOKEN
  token = config["token"]
  if isinstance(token, str) and 1 <= len(token) <= TOKEN_MAX_LENGTH and _is_printable_ascii(token):
    return token
  return ""


def sidecar_interface() -> str:
  interface = load_config().get("interface")
  if isinstance(interface, str) and interface.strip():
    return interface.strip()
  return DEFAULT_INTERFACE


def is_lan_address(address: str) -> bool:
  """True only for IPv4 literals in RFC 1918, loopback (127/8) or link-local (169.254/16) space: never a public address and
  none of the other ranges Python's is_private also counts (0.0.0.0/8, 192.0.2.0/24, 198.18.0.0/15, 203.0.113.0/24, ...)."""
  if is_private_ipv4(address):
    return True
  try:
    ip = ipaddress.IPv4Address(address)
  except ValueError:
    return False
  return any(ip in network for network in LOOPBACK_AND_LINK_LOCAL_NETWORKS)


def is_private_ipv4(address: str) -> bool:
  """True only for RFC 1918 IPv4 literals (10/8, 172.16/12, 192.168/16): what an ARP neighbor of a Wi-Fi link must be."""
  try:
    ip = ipaddress.IPv4Address(address)
  except ValueError:
    return False
  return any(ip in network for network in PRIVATE_NETWORKS)


def parse_default_routes(route_table: str) -> list[tuple[str, int, str]]:
  """(interface, metric, gateway) for every usable IPv4 default route in /proc/net/route text.

  Columns: Iface Destination Gateway Flags RefCnt Use Metric Mask ... Addresses are hex in host byte order,
  flags are hex, metric is decimal.
  """
  routes = []
  for line in route_table.splitlines()[1:]:
    fields = line.split()
    if len(fields) < 8:
      continue
    try:
      destination, gateway, flags = int(fields[1], 16), int(fields[2], 16), int(fields[3], 16)
      metric, mask = int(fields[6]), int(fields[7], 16)
      address = socket.inet_ntoa(struct.pack("=I", gateway))  # raises struct.error for a negative or wider-than-32-bit field
    except (ValueError, struct.error):
      continue
    if destination != 0 or mask != 0 or gateway == 0:
      continue
    if not (flags & RTF_UP) or not (flags & RTF_GATEWAY):
      continue
    routes.append((fields[0], metric, address))
  return routes


def default_gateway(interface: str = DEFAULT_INTERFACE, route_table_path: str | None = None) -> str | None:
  """Default gateway of `interface`; if it has none, the best non-cellular default route; else None."""
  try:
    with open(route_table_path or ROUTE_TABLE_PATH, "rb") as f:
      routes = parse_default_routes(f.read(MAX_ROUTE_TABLE_BYTES).decode("utf-8", "replace"))
  except OSError:
    return None

  candidates = [route for route in routes if route[0] == interface]
  if not candidates:
    candidates = [route for route in routes if not route[0].startswith(CELLULAR_INTERFACE_PREFIXES)]
  if not candidates:
    return None
  return min(candidates, key=lambda route: route[1])[2]


def parse_arp_neighbours(arp_table: str, interface: str) -> list[str]:  # codespell:ignore neighbours
  """RFC 1918 IPv4 addresses of the resolved /proc/net/arp entries on `interface`, in table order, without duplicates.

  Columns: IP address, HW type, Flags, HW address, Mask, Device. Flags are hex; 0x2 (ATF_COM) = resolved, an
  unresolved entry has flags 0x0 and an all-zero HW address. Lines of other interfaces, lines that do not parse,
  non-RFC 1918 addresses and entries for the broadcast HW address are skipped.
  """
  neighbors: list[str] = []
  for line in arp_table.splitlines():
    fields = line.split()
    if len(fields) < 6 or fields[5] != interface:
      continue
    address, flags, hw_address = fields[0], fields[2], fields[3].lower()
    try:
      resolved = bool(int(flags, 16) & ATF_COM)
    except ValueError:
      continue
    if not resolved or hw_address in (UNSET_HW_ADDRESS, BROADCAST_HW_ADDRESS) or not is_private_ipv4(address):
      continue
    if address not in neighbors:
      neighbors.append(address)
  return neighbors


def arp_neighbours(interface: str = DEFAULT_INTERFACE, arp_table_path: str | None = None) -> list[str]:  # codespell:ignore neighbours
  """Parse the ARP table file; [] when it cannot be read."""
  try:
    with open(arp_table_path or ARP_TABLE_PATH, "rb") as f:
      text = f.read(MAX_ARP_BYTES).decode("utf-8", "replace")
  except OSError:
    return []
  return parse_arp_neighbours(text, interface)  # codespell:ignore neighbours


def discovery_candidates(interface: str, gateway: str | None) -> list[str]:
  """Hosts to probe, best first: `gateway` (already checked by the caller), then the ARP neighbors; at most 6."""
  candidates = [gateway] if gateway is not None else []
  for address in arp_neighbours(interface):  # codespell:ignore neighbours
    if address not in candidates:
      candidates.append(address)
  return candidates[:MAX_DISCOVERY_CANDIDATES]


class DeadlineSocket(socket.socket):
  """A TCP socket whose blocking calls are all cut off by one overall deadline (a time.monotonic() value).

  Every connect / send / receive is limited to `operation_timeout` seconds (default PROBE_TIMEOUT_S) and to what is left of the
  deadline, so a peer that answers one byte at a time cannot stretch the total. Used by the /status probe and the route fetch.
  """

  def __init__(self, deadline: float, operation_timeout: float | None = None):
    super().__init__(socket.AF_INET, socket.SOCK_STREAM)
    self._deadline = deadline
    self._operation_timeout = operation_timeout

  def _arm(self) -> None:
    remaining = self._deadline - time.monotonic()
    if remaining <= 0:
      raise TimeoutError("sidecar deadline exceeded")
    self.settimeout(min(PROBE_TIMEOUT_S if self._operation_timeout is None else self._operation_timeout, remaining))

  def connect(self, address) -> None:
    self._arm()
    super().connect(address)

  def sendall(self, data, *args) -> None:
    self._arm()
    super().sendall(data, *args)

  def recv_into(self, *args, **kwargs) -> int:
    self._arm()
    return super().recv_into(*args, **kwargs)


def is_sidecar_status(body: bytes) -> bool:
  """True if `body` is the JSON object GET /status of an openpilot-sidecar route server returns."""
  try:
    status = json.loads(body.decode("utf-8"))
  except (ValueError, RecursionError):
    return False
  if not isinstance(status, dict):
    return False
  api = status.get("api")
  return status.get("service") == SERVICE_NAME and type(api) is int and api >= MIN_API_VERSION


def probe_status(host: str, port: int) -> bool:
  """True if http://host:port/status verifies as an openpilot-sidecar route server. Never raises.

  The peer is untrusted: every failure (refused, timeout, trickling, redirect, non-200, oversized or malformed
  body, wrong JSON) just means "not verified". Only a GET without any credential is sent.
  """
  import http.client  # lazy: this module is imported by processes (Galaxy server, UI) that never probe

  connection = http.client.HTTPConnection(host, port)
  try:
    connection.sock = DeadlineSocket(time.monotonic() + PROBE_DEADLINE_S)  # request() skips connect() when a socket is set
    connection.sock.connect((host, port))
    connection.request("GET", STATUS_PATH, headers=PROBE_HEADERS)
    response = connection.getresponse()
    if response.status != 200:
      return False
    body = response.read(MAX_STATUS_BYTES + 1)
    return len(body) <= MAX_STATUS_BYTES and is_sidecar_status(body)
  except Exception:
    return False
  finally:
    connection.close()


def first_verified(candidates: list[str], port: int) -> str | None:
  """The first candidate, in the given priority order, that probe_status() verifies; None if there is none.

  All candidates are probed at the same time, so dead hosts cost one timeout in total, not one each. Never
  blocks longer than PROBE_DEADLINE_S + PROBE_JOIN_SLACK_S, whatever the candidates do.
  """
  results: dict[str, bool] = {}
  finished = {candidate: threading.Event() for candidate in candidates}

  def probe(candidate: str) -> None:
    try:
      results[candidate] = bool(probe_status(candidate, port))
    except Exception:  # probe_status never raises; a replaced or faulty one must still release the waiter
      results[candidate] = False
    finally:
      finished[candidate].set()

  give_up_at = time.monotonic() + PROBE_DEADLINE_S + PROBE_JOIN_SLACK_S
  for candidate in candidates:
    threading.Thread(target=probe, args=(candidate,), name="sidecar-probe", daemon=True).start()
  for candidate in candidates:
    if finished[candidate].wait(max(0.0, give_up_at - time.monotonic())) and results.get(candidate):
      return candidate
  return None


def invalidate_discovery() -> None:
  """Forget the verified host. Called after every failed route fetch, so the next fetch probes again."""
  global _verified, _discovery_generation
  with _discovery_lock:
    _verified = None
    _discovery_generation += 1


def _discover(interface: str, port: int) -> RouteServer | None:
  global _verified
  with _discovery_lock:
    cached, generation = _verified, _discovery_generation
  if cached is not None and cached.interface == interface and cached.port == port and _monotonic() < cached.expires_at:
    return RouteServer(cached.host, port, SOURCE_VERIFIED)

  gateway = default_gateway(interface)  # read on every discovery: the hotspot subnet can change
  if gateway is not None and not is_lan_address(gateway):
    gateway = None
  candidates = discovery_candidates(interface, gateway)
  verified = first_verified(candidates, port)
  if verified is not None:
    with _discovery_lock:
      if _discovery_generation == generation:  # a failure reported while we were probing wins over this answer
        _verified = _Verified(interface, port, verified, _monotonic() + DISCOVERY_CACHE_TTL_S)
    return RouteServer(verified, port, SOURCE_VERIFIED, len(candidates))
  if gateway is not None:
    return RouteServer(gateway, port, SOURCE_GATEWAY, len(candidates))
  return None


def configured_host() -> str | None:
  """The "host" setting: HOST_AUTO (absent, null or "auto"), a fixed LAN IPv4 address, or None (unusable: fail closed)."""
  host = load_config().get("host")
  if host is None:
    return HOST_AUTO
  if isinstance(host, str):
    if host.strip().lower() == HOST_AUTO:
      return HOST_AUTO
    if is_lan_address(host):
      return host
  return None


def resolve_route_server() -> RouteServer | None:
  """Route server right now, ignoring whether sidecar mode is on. Evaluated on every call: the hotspot subnet can change.

  A fixed host never touches the network. "auto" may probe candidates (bounded, see the module docstring): call it
  from a worker thread, never from a control loop.
  """
  host = configured_host()
  if host is None:
    return None
  port = sidecar_port()
  if host != HOST_AUTO:
    return RouteServer(host, port, SOURCE_FIXED)
  return _discover(sidecar_interface(), port)


def sidecar_route_server() -> RouteServer | None:
  """resolve_route_server() if sidecar mode is on, else None. This is what the route engine asks before every fetch."""
  return resolve_route_server() if sidecar_enabled() else None


def sidecar_host() -> str | None:
  """Address of the route server right now (see resolve_route_server)."""
  server = resolve_route_server()
  return server.host if server is not None else None


def directions_base_url() -> str | None:
  """http://HOST:PORT/directions/v5/mapbox/driving-traffic, or None when sidecar mode is off/unusable."""
  server = sidecar_route_server()
  return server.directions_url if server is not None else None
