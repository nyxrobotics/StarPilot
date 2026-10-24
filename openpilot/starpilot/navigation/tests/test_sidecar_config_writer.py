"""The settings screen's writer: same rules as the readers, atomic, 0600, fail closed, never leaks the token."""

from concurrent.futures import Future
import errno
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import textwrap
import threading
import time

import pytest

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.owner import NavigationOwner
from openpilot.starpilot.navigation.runtime import RouteRuntime
from openpilot.starpilot.navigation.sidecar import SidecarRouteEngine
from openpilot.starpilot.navigation.sidecar_config import (
  DOC_CORRUPT, DOC_MISSING, DOC_OK, DOC_UNREADABLE, SAVE_CHANGED, SAVE_CORRUPT, SAVE_INVALID, SAVE_IO_ERROR, SAVE_SAVED, SAVE_UNCHANGED,
  RouteServer, SOURCE_FIXED, host_problem, normalize_host, parse_port, port_problem, read_config_document, save_config,
)

TOKEN = 'Writer-test-token-0123456789'   # not a real credential; long enough for the 10 character minimum
HOST = '192.168.1.20'
MARKER = 'ZZ-never-print-this-ZZ'         # a token whose text must not show up in any message, log or exception text


class OsProxy:
  """`os` as sidecar_config sees it: every attribute is the real one except the named overrides (nothing else in the process changes)."""

  def __init__(self, **overrides):
    self._overrides = overrides

  def __getattr__(self, name):
    return self._overrides[name] if name in self._overrides else getattr(os, name)


@pytest.fixture
def path(tmp_path, monkeypatch):
  target = tmp_path / 'sidecar.json'
  monkeypatch.setenv(config.CONFIG_ENV, str(target))
  monkeypatch.setattr(config, '_cache', (None, {}))
  return target


def put(path: Path, value, mode=0o600):
  path.write_bytes(value if isinstance(value, bytes) else json.dumps(value).encode())
  path.chmod(mode)


def disk(path: Path):
  return json.loads(path.read_text())


def leftovers(path: Path):
  return sorted(name for name in os.listdir(path.parent) if name.startswith(config.TEMP_PREFIX))


def snapshot(path: Path):
  status = path.stat()
  return path.read_bytes(), status.st_ino, status.st_mtime_ns, stat.S_IMODE(status.st_mode)


# ---------------------------------------------------------------- validation: the rules the readers apply

HOSTS_OK = ['192.168.1.20', '10.0.0.5', '10.255.255.255', '172.16.0.1', '172.31.255.254', '192.168.0.1', '169.254.1.1', '127.0.0.1',
            '127.1.2.3']
HOSTS_BAD = ['', ' ', 'auto', 'AUTO', ' Auto ', '8.8.8.8', '172.15.0.1', '172.32.0.1', '11.0.0.1', '192.169.1.1', '192.168.1.256',
             '192.168.1', '192.168.1.1.1', '192.168.001.010', '192.168.1.20:8765', 'http://192.168.1.20', 'x10.local', '::1',
             'fe80::1', '::ffff:192.168.1.20', '0.0.0.0', '255.255.255.255', '224.0.0.1', '192.0.2.10', '198.51.100.7',
             '203.0.113.9', '100.64.0.1', '１９２.168.1.20', '192.168.1.20\n8.8.8.8', '1.2.3']


@pytest.mark.parametrize('host', HOSTS_OK)
def test_lan_ipv4_addresses_are_accepted_and_stored_canonically(path, host):
  assert host_problem(host) is None and host_problem(f'  {host}\t') is None
  assert normalize_host(f' {host} ') == host
  assert save_config({'host': f' {host} '}).code == SAVE_SAVED
  assert disk(path) == {'host': host}
  assert config.configured_host() == host  # the reader takes exactly what the writer stored


@pytest.mark.parametrize('host', HOSTS_BAD)
def test_everything_else_is_refused_and_the_file_is_not_created(path, host):
  assert host_problem(host)
  result = save_config({'host': host})
  assert (result.ok, result.code, set(result.errors)) == (False, SAVE_INVALID, {'host'})
  assert not path.exists() and not leftovers(path)


@pytest.mark.parametrize('value', [None, 123, 1.5, True, b'192.168.1.20', ['192.168.1.20'], {'host': HOST}])
def test_a_host_must_be_text(path, value):
  assert host_problem(value) == 'The X10 address must be text'
  assert save_config({'host': value}).code == SAVE_INVALID and not path.exists()


def test_host_rule_matches_the_reader_for_every_candidate():
  candidates = [*HOSTS_OK, *HOSTS_BAD]
  candidates += [f'{a}.{b}.1.1' for a in (9, 10, 11, 100, 126, 127, 128, 169, 172, 192) for b in (0, 15, 16, 31, 32, 168, 169, 254)]
  for host in candidates:
    stripped = host.strip()
    expected = config.is_lan_address(stripped) and stripped.lower() != 'auto'
    assert (host_problem(host) is None) is expected, host


@pytest.mark.parametrize('port, ok', [(1, True), (80, True), (8765, True), (65535, True), (0, False), (65536, False), (-1, False),
                                     (1.0, False), (True, False), (False, False), ('8765', False), (None, False), ([8765], False)])
def test_port_must_be_an_int_from_1_to_65535(path, port, ok):
  assert (port_problem(port) is None) is ok
  assert (save_config({'port': port}).code == SAVE_SAVED) is ok


@pytest.mark.parametrize('text, expected', [('8765', 8765), (' 80 ', 80), ('1', 1), ('65535', 65535), ('00080', 80), ('0', None),
                                            ('65536', None), ('', None), ('abc', None), ('80a', None), ('-1', None), ('+80', None),
                                            ('1e3', None), ('80.0', None), ('999999', None), ('８０', None), ('²', None),
                                            ('8 0', None)])
def test_typed_ports_are_ascii_digits_in_range(text, expected):
  assert parse_port(text) == expected


@pytest.mark.parametrize('value', [1, 0, 'true', 'false', None, 1.0, [True]])
def test_enabled_must_be_a_real_boolean(path, value):
  result = save_config({'enabled': value})
  assert (result.code, result.errors) == (SAVE_INVALID, {'enabled': 'Enabled must be on or off'}) and not path.exists()


@pytest.mark.parametrize('length, ok', [(0, False), (9, False), (10, True), (128, True), (129, False)])
def test_the_writer_applies_the_10_to_128_token_rule(path, length, ok):
  token = ('!~' + 'x' * length)[:length]
  result = save_config({'token': token})
  assert (result.code == SAVE_SAVED) is ok and path.exists() is ok
  if ok:
    assert disk(path) == {'token': token} and config.sidecar_token() == token
  for bad in ('with space 12345', 'tab\t0123456789', 'café-0123456789', 'del\x7f0123456789'):
    assert save_config({'token': bad}).code == SAVE_INVALID


def test_unknown_removed_and_conflicting_changes_are_refused_without_touching_the_file(path):
  put(path, {'enabled': False, 'interface': 'wlan1'})
  before = snapshot(path)
  for call in (lambda: save_config({'interface': 'eth0'}), lambda: save_config({'whatever': 1}),
               lambda: save_config({}, remove=['enabled']), lambda: save_config({}, remove=['interface']),
               lambda: save_config({'port': 9000}, remove=['port']), lambda: save_config({'port': 0}),
               lambda: save_config({'port': 9000, 'host': 'auto'})):
    result = call()
    assert (result.ok, result.code) == (False, SAVE_INVALID) and result.errors and result.message in result.errors.values()
    assert snapshot(path) == before


# ---------------------------------------------------------------- merging, enabling and what the readers then see

def test_a_new_file_lists_the_keys_in_the_documented_order_with_mode_0600(path):
  result = save_config({'token': TOKEN, 'port': 9000, 'host': HOST, 'enabled': True})
  assert (result.ok, result.code, result.message, result.errors, result.backup) == (True, SAVE_SAVED, 'Saved', {}, None)
  assert list(disk(path)) == ['enabled', 'host', 'port', 'token']
  assert stat.S_IMODE(path.stat().st_mode) == 0o600 and path.read_text().endswith('}\n')
  assert not leftovers(path)


def test_unknown_keys_and_their_values_are_preserved(path):
  original = {'note': '日本語', 'interface': 'wlan1', 'nested': {'a': [1, 2.5, None, True]}, 'enabled': False, 'host': HOST}
  put(path, original)
  assert save_config({'port': 9001}).code == SAVE_SAVED
  assert disk(path) == {**original, 'port': 9001}
  assert list(disk(path))[:5] == list(original)  # existing keys keep their place, new ones follow
  assert save_config({}, remove=['port']).code == SAVE_SAVED and disk(path) == original


def test_auto_host_in_an_existing_file_is_left_alone_unless_a_real_address_replaces_it(path):
  put(path, {'enabled': False, 'host': 'auto', 'interface': 'wlan0'})
  assert save_config({'token': TOKEN, 'port': 8765}).code == SAVE_SAVED
  assert disk(path)['host'] == 'auto' and config.configured_host() == config.HOST_AUTO
  assert save_config({'enabled': True}).code == SAVE_INVALID  # on needs a fixed address: the bridge rejects auto
  assert save_config({'host': HOST, 'enabled': True}).code == SAVE_SAVED
  assert disk(path)['host'] == HOST and disk(path)['enabled'] is True


def test_turning_it_on_needs_a_fixed_address_and_a_strong_token(path):
  assert 'address' in save_config({'enabled': True}).message
  assert 'address' in save_config({'enabled': True, 'token': TOKEN}).message
  assert 'token' in save_config({'enabled': True, 'host': HOST}).message
  assert not path.exists()
  assert save_config({'host': HOST, 'token': TOKEN}).code == SAVE_SAVED and disk(path)['host'] == HOST
  assert save_config({'enabled': True}).code == SAVE_SAVED
  assert config.sidecar_enabled() and config.sidecar_token() == TOKEN
  assert config.resolve_route_server() == RouteServer(HOST, 8765, SOURCE_FIXED)


def test_the_enable_rule_covers_removals_and_stored_values_but_never_blocks_turning_off(path):
  put(path, {'enabled': True, 'host': HOST, 'token': 'short'})  # a hand-written legacy file
  assert save_config({'port': 9000}).code == SAVE_SAVED                      # unrelated edits are not held back
  assert save_config({'host': '10.1.2.3'}).code == SAVE_INVALID              # touching host or token re-checks the whole result
  assert save_config({}, remove=['token']).code == SAVE_INVALID
  assert save_config({'token': TOKEN}).code == SAVE_SAVED
  put(path, {'enabled': True, 'host': ' 192.168.1.5', 'token': TOKEN})       # a blank the reader would not strip
  assert save_config({'token': TOKEN + 'x'}).code == SAVE_INVALID
  assert save_config({'enabled': False}).code == SAVE_SAVED and disk(path)['enabled'] is False


def test_clear_disables_and_removes_the_token_but_keeps_the_address(path):
  assert save_config({'enabled': True, 'host': HOST, 'port': 9000, 'token': TOKEN}).code == SAVE_SAVED
  result = save_config({'enabled': False}, remove=['token'])
  assert result.code == SAVE_SAVED and disk(path) == {'enabled': False, 'host': HOST, 'port': 9000}
  assert not config.sidecar_enabled() and config.sidecar_route_server() is None
  assert save_config({'enabled': False}, remove=['token']).code == SAVE_UNCHANGED


def test_saving_the_same_values_writes_nothing(path):
  assert save_config({'enabled': False, 'host': HOST, 'port': 8765, 'token': TOKEN}).code == SAVE_SAVED
  before = snapshot(path)
  time.sleep(0.01)
  for same in ({}, {'host': HOST}, {'port': 8765, 'enabled': False}, {'token': TOKEN}, {'host': f' {HOST} '}):
    result = save_config(same)
    assert (result.ok, result.code, result.message) == (True, SAVE_UNCHANGED, 'Nothing to change')
  assert snapshot(path) == before and not leftovers(path)


def test_the_comparison_tells_true_from_1_and_a_text_port_from_a_number(path):
  put(path, {'enabled': 1, 'host': HOST, 'port': '8765', 'token': TOKEN})  # neither is what the reader treats as set
  assert not config.sidecar_enabled()
  assert save_config({'enabled': True}).code == SAVE_SAVED and config.sidecar_enabled()
  put(path, {'host': HOST, 'port': '8765'})
  assert save_config({'port': 8765}).code == SAVE_SAVED and disk(path)['port'] == 8765


def test_a_running_route_runtime_follows_every_save_without_a_restart(path, tmp_path, monkeypatch):
  """navigationd re-reads the config on every update; another process's cached copy is outdated by the rename's new inode."""
  class MetricOwner(NavigationOwner):
    def _is_metric(self):
      return True

  put(path, {'enabled': False, 'host': HOST})
  owner = MetricOwner(tmp_path / 'navigation', runtime_source=lambda: None)
  saved = owner.configure({'enabled': True, 'token': 'mapbox-test-token'}, '0', True)
  owner.select({'name': 'Shibuya', 'latitude': 35.65229, 'longitude': 139.701592}, saved['revision'], True)
  jobs = []

  class Executor:
    def submit(self, function, *args):
      future = Future()
      jobs.append((function, args, future))
      return future

  runtime = RouteRuntime(owner, executor=Executor())
  position = (9_999_000_000, (139.699228, 35.66484), 2., 90.)
  assert runtime.update(10_000_000_000, position, 9_000_000_000)['status'] == 'routing' and jobs[0][1][0] == 'mapbox-test-token'

  stale = config._cache  # what this reader cached before the settings screen (another process) saved
  assert stale[0] is not None
  assert save_config({'host': HOST, 'port': 9000, 'token': TOKEN, 'enabled': True}).ok
  monkeypatch.setattr(config, '_cache', stale)     # a different process keeps its own cache: only the file signature can tell
  assert runtime.update(11_000_000_000, (10_999_000_000, position[1], 2., 90.), 9_000_000_000)['status'] == 'routing'
  assert runtime.sidecar_mode and isinstance(runtime.engine, SidecarRouteEngine)
  assert len(jobs) == 2 and jobs[1][1][0] == TOKEN and jobs[0][2].cancelled()
  assert config.directions_base_url() == f'http://{HOST}:9000' + config.DIRECTIONS_PATH

  stale = config._cache
  assert save_config({'enabled': False}).ok
  monkeypatch.setattr(config, '_cache', stale)
  assert runtime.update(12_000_000_000, (11_999_000_000, position[1], 2., 90.), 9_000_000_000)['status'] == 'routing'
  assert not runtime.sidecar_mode and len(jobs) == 3 and jobs[2][1][0] == 'mapbox-test-token'


# ---------------------------------------------------------------- reading what is on disk

def test_read_config_document_says_why_a_file_is_unusable(path):
  assert read_config_document().state == DOC_MISSING
  put(path, {'enabled': True})
  document = read_config_document()
  assert (document.state, document.config) == (DOC_OK, {'enabled': True}) and document.signature
  for content in (b'not json', b'[1]', b'"text"', b'\xff\xfe\x00', b'{"a":', b'\xef\xbb\xbf{}', b' ' * (config.MAX_CONFIG_BYTES + 1)):
    path.write_bytes(content)
    assert read_config_document().state == DOC_CORRUPT, content[:10]
  path.unlink()
  path.mkdir()
  assert read_config_document().state == DOC_UNREADABLE


# ---------------------------------------------------------------- permissions and atomicity

def test_the_file_is_0600_before_it_is_renamed_even_if_the_old_one_was_open_to_everyone(path, monkeypatch):
  put(path, {'enabled': False}, mode=0o644)
  seen = []

  def replace(source, target):
    seen.append((stat.S_IMODE(os.stat(source).st_mode), Path(source).parent == path.parent, Path(source).name.startswith('.sidecar-')))
    return os.replace(source, target)

  monkeypatch.setattr(config, 'os', OsProxy(replace=replace))
  assert save_config({'token': TOKEN}).code == SAVE_SAVED
  assert seen == [(0o600, True, True)]  # a temp file in the same directory, already 0600 when it is swapped in
  assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize('step, number', [('fchmod', errno.EPERM), ('write', errno.ENOSPC), ('fsync', errno.EIO),
                                          ('replace', errno.EXDEV), ('mkstemp', errno.EACCES)])
def test_any_failing_step_leaves_the_existing_file_untouched_and_cleans_up(path, monkeypatch, step, number):
  put(path, {'enabled': False, 'host': HOST, 'extra': 1})
  before = snapshot(path)

  def boom(*_args, **_kwargs):
    raise OSError(number, os.strerror(number))

  with monkeypatch.context() as patch:
    if step == 'mkstemp':
      patch.setattr(tempfile, 'mkstemp', boom)
    else:
      patch.setattr(config, 'os', OsProxy(**{step: boom}))
    result = save_config({'token': MARKER + '0123456789', 'port': 9000})
  assert (result.ok, result.code) == (False, SAVE_IO_ERROR)
  assert errno.errorcode[number] in result.message and MARKER not in result.message
  assert snapshot(path) == before
  assert not leftovers(path)


def test_a_failing_directory_sync_after_the_rename_does_not_undo_the_save(path, monkeypatch):
  put(path, {'enabled': False})

  def fsync(fd):
    if stat.S_ISDIR(os.fstat(fd).st_mode):
      raise OSError(errno.EINVAL, 'not supported')
    return os.fsync(fd)

  monkeypatch.setattr(config, 'os', OsProxy(fsync=fsync))
  assert save_config({'port': 9000}).code == SAVE_SAVED and disk(path) == {'enabled': False, 'port': 9000}


def test_a_short_write_does_not_publish_a_truncated_file(path, monkeypatch):
  put(path, {'enabled': False, 'host': HOST})
  before = snapshot(path)
  writes = []

  def short_then_fail(fd, data):
    writes.append(len(data))
    if len(writes) == 1:
      return os.write(fd, bytes(data)[:7])  # a first call that writes only 7 bytes is legal: the loop must go on
    raise OSError(errno.ENOSPC, 'no space left')

  monkeypatch.setattr(config, 'os', OsProxy(write=short_then_fail))
  result = save_config({'token': TOKEN})
  assert result.code == SAVE_IO_ERROR and len(writes) == 2 and writes[1] == writes[0] - 7
  assert snapshot(path) == before and not leftovers(path)


def test_a_short_write_that_completes_is_still_one_whole_file(path, monkeypatch):
  monkeypatch.setattr(config, 'os', OsProxy(write=lambda fd, data: os.write(fd, bytes(data)[:3])))
  result = save_config({'enabled': True, 'host': HOST, 'port': 8765, 'token': TOKEN})
  assert result.code == SAVE_SAVED
  assert disk(path) == {'enabled': True, 'host': HOST, 'port': 8765, 'token': TOKEN}


def test_a_crash_before_the_rename_keeps_the_old_file_and_the_next_save_removes_the_leftover(path):
  put(path, {'enabled': False, 'host': HOST})
  before = snapshot(path)
  child = textwrap.dedent(f'''
    import os, sys
    from openpilot.starpilot.navigation import sidecar_config as c
    os.replace = lambda *args: os._exit(86)   # the power fails right after the new bytes were flushed
    c.save_config({{'token': {TOKEN!r}}}, path={str(path)!r})
    sys.exit(0)
  ''')
  done = subprocess.run([sys.executable, '-c', child], env=dict(os.environ), capture_output=True, timeout=60, check=False)
  assert done.returncode == 86, done.stderr.decode()[-2000:]
  assert snapshot(path) == before                    # the live file was never touched
  stale = leftovers(path)
  assert len(stale) == 1
  stale_path = path.parent / stale[0]
  assert stat.S_IMODE(stale_path.stat().st_mode) == 0o600 and TOKEN in stale_path.read_text()  # complete, private, but never live
  assert save_config({'port': 9100}).code == SAVE_SAVED
  assert stale_path.exists()                         # a fresh temp file might belong to a write that is still running
  old = time.time_ns() - 3600 * 10**9
  os.utime(stale_path, ns=(old, old))
  assert save_config({'port': 9200}).code == SAVE_SAVED
  assert not leftovers(path) and disk(path)['port'] == 9200


def test_leftover_cleanup_only_removes_our_own_old_temp_files(path):
  put(path, {'enabled': False})
  old = time.time_ns() - 3600 * 10**9
  doomed = path.parent / '.sidecar-abcd.tmp'
  keep = [path.parent / '.sidecar-fresh.tmp', path.parent / '.other-abcd.tmp', path.parent / 'sidecar-abcd.tmp',
          path.parent / '.sidecar-abcd.tmp.bak', path.parent / 'sidecar.json.bak']
  for item in (doomed, *keep):
    item.write_text('x')
  for item in (doomed, *keep[1:]):
    os.utime(item, ns=(old, old))
  assert save_config({'port': 9000}).code == SAVE_SAVED
  assert not doomed.exists() and all(item.exists() for item in keep)


def test_another_writer_getting_in_between_is_detected_and_its_file_is_kept(path, monkeypatch):
  put(path, {'enabled': False, 'host': HOST})
  calls = []

  def intruder(fd):
    os.fsync(fd)
    calls.append(fd)
    if len(calls) == 1:  # the temp file has just been flushed; an administrator edits the live file over SSH right now
      path.write_text(json.dumps({'enabled': False, 'host': '10.0.0.9', 'by': 'ssh'}))

  with monkeypatch.context() as patch:
    patch.setattr(config, 'os', OsProxy(fsync=intruder))
    result = save_config({'token': TOKEN})
  assert (result.ok, result.code) == (False, SAVE_CHANGED) and 'try again' in result.message
  assert disk(path) == {'enabled': False, 'host': '10.0.0.9', 'by': 'ssh'} and not leftovers(path)
  assert save_config({'token': TOKEN}).code == SAVE_SAVED and disk(path)['by'] == 'ssh'  # the retry merges onto the newer file


def test_a_file_that_appears_while_saving_is_not_overwritten(path, monkeypatch):
  calls = []

  def intruder(fd):
    os.fsync(fd)
    calls.append(fd)
    if len(calls) == 1:
      path.write_text('{"by": "ssh"}')

  monkeypatch.setattr(config, 'os', OsProxy(fsync=intruder))
  result = save_config({'port': 9000})
  assert result.code == SAVE_CHANGED and disk(path) == {'by': 'ssh'} and not leftovers(path)


def test_concurrent_saves_never_expose_a_partial_file_and_lose_no_update(path):
  assert save_config({'host': HOST}).code == SAVE_SAVED
  stop = threading.Event()
  problems, reads = [], []

  def reader():
    while not stop.wait(0.001):  # a tight loop would hold the GIL against the writers' fsync calls and make the test minutes long under load
      reads.append(1)
      document = read_config_document()
      if document.state != DOC_OK or document.config.get('host') != HOST:
        problems.append(document.state)

  def writer(index):
    for round_ in range(6):  # every save is fsynced: more rounds only make a busy disk slower, not the check stronger
      result = save_config({'port': 1000 + index * 100 + round_})
      if not result.ok:
        problems.append(result.code)

  watcher = threading.Thread(target=reader)
  watcher.start()
  writers = [threading.Thread(target=writer, args=(index,)) for index in range(4)]
  for thread in writers:
    thread.start()
  for thread in writers:
    thread.join(timeout=60)
  stop.set()
  watcher.join(timeout=10)
  assert problems == [] and reads  # the reader ran alongside the writers (how often depends on how fast the disk is)
  assert disk(path)['host'] == HOST and 1000 <= disk(path)['port'] < 1400 and not leftovers(path)


def test_concurrent_edits_of_different_settings_are_all_kept(path):
  results = []
  threads = [threading.Thread(target=lambda value=value: results.append(save_config(value))) for value in
             ({'host': HOST}, {'port': 9000}, {'token': TOKEN}, {'enabled': False})]
  for thread in threads:
    thread.start()
  for thread in threads:
    thread.join(timeout=30)
  assert [result.code for result in results] == [SAVE_SAVED] * 4
  assert disk(path) == {'enabled': False, 'host': HOST, 'port': 9000, 'token': TOKEN}  # the lock serializes them; none is lost


def test_a_symlinked_config_is_written_through_and_stays_a_symlink(path, tmp_path):
  real = tmp_path / 'elsewhere'
  real.mkdir()
  target = real / 'real.json'
  put(target, {'enabled': False})
  path.symlink_to(target)
  assert save_config({'port': 9000}).code == SAVE_SAVED
  assert path.is_symlink() and disk(target) == {'enabled': False, 'port': 9000} and not leftovers(target)


def test_the_loader_cache_is_dropped_by_a_save(path):
  put(path, {'enabled': False, 'host': HOST})
  assert config.load_config()['host'] == HOST
  assert config._cache[0] is not None
  assert save_config({'port': 9000}).code == SAVE_SAVED
  assert config._cache == (None, {}) and config.load_config()['port'] == 9000


# ---------------------------------------------------------------- damaged files

DAMAGED = [b'not json', b'[]', b'null', b'"text"', b'\xff\xfe\x00{', b'{"enabled": ', b'\xef\xbb\xbf{"enabled": false}', b'{}}',
           b' ' * (config.MAX_CONFIG_BYTES + 1)]


@pytest.mark.parametrize('content', DAMAGED)
def test_a_damaged_file_is_refused_not_overwritten(path, content):
  path.write_bytes(content)
  before = snapshot(path)
  result = save_config({'port': 9000})
  assert (result.ok, result.code) == (False, SAVE_CORRUPT) and 'damaged' in result.message and 'backup' in result.message
  assert snapshot(path) == before
  assert not leftovers(path) and sorted(os.listdir(path.parent)) == ['sidecar.json']


def test_values_that_json_cannot_write_back_are_refused(path):
  path.write_text('{"enabled": false, "odd": NaN}')  # Python reads NaN, strict JSON cannot hold it
  before = snapshot(path)
  assert save_config({'port': 9000}).code == SAVE_CORRUPT and snapshot(path) == before


@pytest.mark.parametrize('content', DAMAGED)
def test_replacing_a_damaged_file_keeps_the_original_bytes_as_a_private_backup(path, content):
  path.write_bytes(content)
  result = save_config({'enabled': False}, remove=['token'], replace_corrupt=True)
  assert (result.ok, result.code) == (True, SAVE_SAVED) and result.backup
  assert Path(result.backup).read_bytes() == content and stat.S_IMODE(os.stat(result.backup).st_mode) == 0o600
  assert Path(result.backup).name.startswith('sidecar.json.bak-') and disk(path) == {'enabled': False}
  assert read_config_document().state == DOC_OK and not leftovers(path)


def test_identical_damage_reuses_the_backup_and_different_damage_gets_its_own(path):
  path.write_bytes(b'broken one')
  first = save_config({'enabled': False}, replace_corrupt=True).backup
  assert first is not None
  stamp = int(first.split('.bak-')[1])
  for content in (b'broken one', b'broken two'):
    path.write_bytes(content)
    os.utime(path, ns=(stamp, stamp))  # the backup name carries the damaged file's mtime
    backup = save_config({'enabled': False}, replace_corrupt=True).backup
    assert backup is not None
    if content == b'broken one':
      assert backup == first
    else:
      assert backup != first and Path(backup).read_bytes() == b'broken two' and Path(first).read_bytes() == b'broken one'


def test_a_healthy_file_is_never_backed_up_and_replace_corrupt_changes_nothing_for_it(path):
  put(path, {'enabled': False, 'host': HOST})
  result = save_config({'port': 9000}, replace_corrupt=True)
  assert (result.code, result.backup) == (SAVE_SAVED, None) and disk(path)['host'] == HOST
  assert sorted(os.listdir(path.parent)) == ['sidecar.json']


def test_a_file_too_large_to_back_up_is_not_replaced(path, monkeypatch):
  monkeypatch.setattr(config, 'MAX_BACKUP_BYTES', 8)
  path.write_bytes(b'x' * 100)
  result = save_config({'enabled': False}, replace_corrupt=True)
  assert result.code == SAVE_IO_ERROR and path.read_bytes() == b'x' * 100 and sorted(os.listdir(path.parent)) == ['sidecar.json']


def test_an_unreadable_path_is_an_io_error_and_is_left_alone(path):
  path.mkdir()
  result = save_config({'port': 9000})
  assert (result.ok, result.code) == (False, SAVE_IO_ERROR) and path.is_dir() and not leftovers(path)


def test_a_missing_directory_is_an_io_error(tmp_path):
  result = save_config({'port': 9000}, path=str(tmp_path / 'missing' / 'sidecar.json'))
  assert (result.ok, result.code) == (False, SAVE_IO_ERROR) and not (tmp_path / 'missing').exists()


# ---------------------------------------------------------------- the token never leaks

def test_no_result_log_or_exception_text_contains_the_token(path, monkeypatch, capsys, caplog):
  secret = MARKER + '-0123456789'
  texts = []

  def keep(result):
    texts.extend([result.message, *result.errors.values(), repr(result), result.backup or ''])
    return result

  keep(save_config({'token': secret + ' bad'}))                                                    # invalid
  keep(save_config({'token': secret, 'host': HOST, 'enabled': True}))                              # saved
  keep(save_config({'token': secret}))                                                             # unchanged
  keep(save_config({'enabled': True, 'host': HOST, 'token': secret}, path=str(path.parent / 'no' / 'dir.json')))  # io error
  path.write_bytes(b'broken')
  keep(save_config({'token': secret}))                                                             # damaged
  keep(save_config({'token': secret}, replace_corrupt=True))                                       # replaced
  monkeypatch.setattr(config, 'os', OsProxy(replace=lambda *_args: (_ for _ in ()).throw(OSError(errno.EIO, 'boom'))))
  keep(save_config({'token': secret + 'x'}))                                                       # failing rename
  assert texts and all(MARKER not in text for text in texts)
  captured = capsys.readouterr()
  assert MARKER not in captured.out + captured.err and MARKER not in caplog.text
