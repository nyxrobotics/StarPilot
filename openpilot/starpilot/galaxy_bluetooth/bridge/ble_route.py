"""Bounded, local route exchange between navigationd and the paired BLE bridge.

The route worker submits one relative Sidecar URL over a private Unix socket.
The paired Companion polls for it and returns a gzip body in numbered GATT
requests. Nothing here opens a network listener or logs coordinates or tokens.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import json
import os
import re
import secrets
import socket
import socketserver
import stat
import struct
import threading
import time
from urllib.parse import urlsplit
import zlib


SOCKET_PATH = '/dev/shm/starpilot-sidecar-ble.sock'
ROUTE_DEADLINE_S = 90.0
MAX_ROUTE_BYTES = 8 * 1024 * 1024
MAX_CHUNK_BYTES = 64 * 1024
MAX_TARGET_BYTES = 8192
MAX_JOBS = 2
_ID = re.compile(r'[0-9a-f]{32}\Z')
_ROUTE_PATH = re.compile(r'/directions/v5/mapbox/driving-traffic/[^/?#]{1,256}\Z')


def _exact(socket_obj: socket.socket, count: int) -> bytes:
  data = bytearray()
  while len(data) < count:
    chunk = socket_obj.recv(count - len(data))
    if not chunk:
      raise OSError('Sidecar BLE connection closed')
    data += chunk
  return bytes(data)


def _frame(socket_obj: socket.socket, payload: bytes) -> None:
  socket_obj.sendall(struct.pack('>I', len(payload)) + payload)


def valid_target(target: str) -> bool:
  if type(target) is not str or len(target.encode('utf-8', 'ignore')) > MAX_TARGET_BYTES:
    return False
  if any(ord(char) < 32 or ord(char) == 127 for char in target):
    return False
  parts = urlsplit(target)
  return (not parts.scheme and not parts.netloc and not parts.fragment and
          _ROUTE_PATH.fullmatch(parts.path) is not None and bool(parts.query) and
          '%' not in parts.path and '\\' not in parts.path)


@dataclass
class _Job:
  id: str
  target: str
  deadline: float
  next_sequence: int = 0
  compressed: bytearray = field(default_factory=bytearray)
  body: bytes | None = None
  error: str | None = None
  finished: float | None = None


class RouteBroker:
  """A small in-memory exchange shared by the Unix handler and BLE proxy."""

  def __init__(self):
    self.condition = threading.Condition()
    self.jobs: dict[str, _Job] = {}

  def _prune(self, now: float) -> None:
    for job_id, job in list(self.jobs.items()):
      if now >= job.deadline or (job.finished is not None and now - job.finished > 30):
        del self.jobs[job_id]

  def request(self, target: str) -> tuple[int, bytes]:
    if not valid_target(target):
      raise ValueError('Invalid Sidecar route target')
    with self.condition:
      now = time.monotonic()
      self._prune(now)
      if sum(job.finished is None for job in self.jobs.values()) >= MAX_JOBS:
        raise RuntimeError('Sidecar BLE route queue is full')
      job = _Job(secrets.token_hex(16), target, now + ROUTE_DEADLINE_S)
      self.jobs[job.id] = job
      self.condition.notify_all()
      while job.finished is None:
        remaining = job.deadline - time.monotonic()
        if remaining <= 0:
          job.error = 'Sidecar BLE route timed out'
          job.finished = time.monotonic()
          break
        self.condition.wait(remaining)
      if job.error is not None:
        raise RuntimeError(job.error)
      return 200, job.body or b''

  def poll(self) -> dict[str, str | int] | None:
    with self.condition:
      self._prune(time.monotonic())
      for job in self.jobs.values():
        if job.finished is None:
          # The sender can reconnect after a lost GATT acknowledgement and
          # resume from the first chunk that was not committed here.
          return {'id': job.id, 'target': job.target, 'next_sequence': job.next_sequence}
      return None

  def fail(self, job_id: str) -> None:
    if _ID.fullmatch(job_id) is None:
      raise ValueError('Invalid route ID')
    with self.condition:
      job = self.jobs.get(job_id)
      if job is None:
        raise KeyError('Route job expired')
      if job.finished is None:
        job.error = 'Sidecar route could not be fetched over BLE'
        job.finished = time.monotonic()
        self.condition.notify_all()

  def chunk(self, job_id: str, sequence: int, data: bytes, *, last: bool, status: int, encoding: str) -> int:
    if _ID.fullmatch(job_id) is None or type(sequence) is not int or sequence < 0:
      raise ValueError('Invalid route chunk')
    if type(data) is not bytes or len(data) > MAX_CHUNK_BYTES:
      raise ValueError('Route chunk is too large')
    if type(last) is not bool or status != 200 or encoding != 'gzip':
      raise ValueError('Unsupported route transfer')
    with self.condition:
      job = self.jobs.get(job_id)
      if job is None:
        raise KeyError('Route job expired')
      if time.monotonic() >= job.deadline:
        self.fail_locked(job, 'Sidecar BLE route timed out')
        raise KeyError('Route job expired')
      if sequence < job.next_sequence:
        return job.next_sequence  # Retry after a lost acknowledgement.
      if job.finished is not None or sequence != job.next_sequence:
        raise ValueError('Out-of-order route chunk')
      if len(job.compressed) + len(data) > MAX_ROUTE_BYTES:
        self.fail_locked(job, 'Sidecar BLE route is too large')
        raise ValueError('Route exceeds size limit')
      job.compressed += data
      job.next_sequence += 1
      if last:
        try:
          inflater = zlib.decompressobj(wbits=16 + zlib.MAX_WBITS)
          body = inflater.decompress(bytes(job.compressed), MAX_ROUTE_BYTES + 1)
          if (len(body) > MAX_ROUTE_BYTES or not inflater.eof or inflater.unconsumed_tail or inflater.unused_data or
              inflater.flush()):
            raise ValueError('Invalid or oversized gzip route')
        except zlib.error:
          self.fail_locked(job, 'Invalid gzip route')
          raise ValueError('Invalid gzip route') from None
        except ValueError:
          self.fail_locked(job, 'Invalid or oversized gzip route')
          raise
        job.body = body
        job.compressed.clear()
        job.finished = time.monotonic()
        self.condition.notify_all()
      return job.next_sequence

  def fail_locked(self, job: _Job, message: str) -> None:
    job.error = message
    job.finished = time.monotonic()
    job.compressed.clear()
    self.condition.notify_all()


class _UnixHandler(socketserver.BaseRequestHandler):
  def handle(self) -> None:
    self.request.settimeout(ROUTE_DEADLINE_S + 5)
    try:
      length = struct.unpack('>I', _exact(self.request, 4))[0]
      if not 1 <= length <= MAX_TARGET_BYTES:
        raise ValueError('Invalid request length')
      target = _exact(self.request, length).decode('utf-8')
      status, body = self.server.broker.request(target)
      header = json.dumps({'status': status, 'size': len(body)}, separators=(',', ':')).encode()
      _frame(self.request, header)
      self.request.sendall(body)
    except (OSError, UnicodeError, ValueError, RuntimeError) as error:
      try:
        _frame(self.request, json.dumps({'status': 503, 'size': 0, 'error': str(error)[:128]}).encode())
      except OSError:
        pass


class _UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
  daemon_threads = True
  allow_reuse_address = False

  def __init__(self, path: str, broker: RouteBroker):
    self.broker = broker
    super().__init__(path, _UnixHandler)


class RouteBrokerServer:
  def __init__(self, path: str = SOCKET_PATH):
    self.path = path
    self.broker = RouteBroker()
    self.server: _UnixServer | None = None
    self.thread: threading.Thread | None = None

  def start(self) -> RouteBroker:
    try:
      info = os.lstat(self.path)
    except FileNotFoundError:
      pass
    else:
      if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
        raise RuntimeError('Unsafe Sidecar BLE socket path')
      os.unlink(self.path)
    self.server = _UnixServer(self.path, self.broker)
    os.chmod(self.path, 0o600)
    self.thread = threading.Thread(target=self.server.serve_forever, name='sidecar-ble-route', daemon=True)
    self.thread.start()
    return self.broker

  def close(self) -> None:
    if self.server is None:
      return
    self.server.shutdown()
    self.server.server_close()
    self.server = None
    try:
      info = os.lstat(self.path)
      if stat.S_ISSOCK(info.st_mode) and info.st_uid == os.getuid():
        os.unlink(self.path)
    except FileNotFoundError:
      pass


def fetch_route(target: str, path: str = SOCKET_PATH) -> tuple[int, bytes]:
  """Submit a route to the running BLE bridge, returning bounded raw JSON."""
  if not valid_target(target):
    raise ValueError('Invalid Sidecar route target')
  with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
    client.settimeout(ROUTE_DEADLINE_S + 5)
    client.connect(path)
    _frame(client, target.encode('utf-8'))
    length = struct.unpack('>I', _exact(client, 4))[0]
    if not 1 <= length <= 1024:
      raise ValueError('Invalid Sidecar BLE answer')
    header = json.loads(_exact(client, length))
    status, size = header.get('status'), header.get('size')
    if type(status) is not int or type(size) is not int or not 0 <= size <= MAX_ROUTE_BYTES:
      raise ValueError('Invalid Sidecar BLE answer')
    if status != 200:
      raise OSError('Sidecar BLE route unavailable')
    return status, _exact(client, size)
