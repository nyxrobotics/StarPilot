"""The X10 sidecar settings controller: edits are checked and written off the UI thread, the X10 check never blocks a frame."""

from concurrent.futures import Future
import json
import os
from pathlib import Path
import stat
import threading
import time

import pytest

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.sidecar_status import Verdict, X10Status
from openpilot.starpilot.ui import sidecar_flow
from openpilot.starpilot.ui.sidecar_flow import (
  BUSY_NOTICE, LOCKED_NOTICE, POLL_INTERVAL_S, READ_INTERVAL_S, SidecarFlow, SidecarView, age_text, describe, group_token,
)

HOST = '192.168.1.20'
TOKEN = 'Flow-test-token-0123456789abcdef'  # not a real credential
READY = X10Status(Verdict.READY, HOST, 8765, 'app 1.4.2, up 2 h', api=1, version='1.4.2', engine='ready', uptime_s=7200, http_status=200)
DEAD = X10Status(Verdict.UNREACHABLE, HOST, 8765, 'connection refused')


class ManualExecutor:
  """Collects submitted work so that a test decides when each job runs and in which order."""

  def __init__(self):
    self.jobs: list[tuple[Future, object]] = []
    self.shutdown_calls = 0

  def submit(self, function, *args):
    future = Future()
    self.jobs.append((future, lambda: function(*args)))
    return future

  def pending(self):
    return [job for job in self.jobs if not job[0].done()]

  def run(self, index=0):
    future, job = self.pending()[index]
    try:
      future.set_result(job())
    except Exception as error:
      future.set_exception(error)

  def run_all(self):
    while self.pending():
      self.run()

  def shutdown(self, **_kwargs):
    self.shutdown_calls += 1


class Env:
  def __init__(self, tmp_path):
    self.path = tmp_path / 'sidecar.json'
    self.clock = [100.0]
    self.executor = ManualExecutor()
    self.allowed = [True]
    self.checks: list[tuple[str, int]] = []
    self.answer = [READY]
    self.flow = SidecarFlow(lambda: self.allowed[0], path=str(self.path), check=self.check, now=lambda: self.clock[0], executor=self.executor)

  def check(self, host, port):
    self.checks.append((host, port))
    return self.answer[0]._replace(host=host, port=port)  # an X10 answers for the address it was asked at

  def tick(self, seconds):
    self.clock[0] += seconds

  def put(self, value):
    self.path.write_text(json.dumps(value))

  def disk(self):
    return json.loads(self.path.read_text())

  def settle(self):
    """Run every pending job, then one frame to collect the results."""
    self.executor.run_all()
    return self.flow.update()


@pytest.fixture
def env(tmp_path):
  return Env(tmp_path)


# ---------------------------------------------------------------- the view

def test_a_fresh_device_has_nothing_set(env):
  view = env.flow.update()
  assert view == SidecarView(document=config.DOC_MISSING, editable=True)
  words = describe(view)
  assert (words.status_title, words.address, words.token, words.port, words.toggle_value, words.test) == \
         ('X10 address not set', 'not set', 'not set', '8765 (default)', 'set up first', 'set address first')
  assert not view.can_enable and view.readable


def test_the_view_shows_the_saved_file_and_never_the_token(env):
  env.put({'enabled': True, 'host': HOST, 'port': 9000, 'token': TOKEN})
  view = env.flow.update()
  assert (view.document, view.enabled, view.host, view.port, view.token_length, view.token_problem) == \
         (config.DOC_OK, True, HOST, 9000, len(TOKEN), '')
  assert view.can_enable and view.has_token and view.has_host
  assert TOKEN not in repr(view) and TOKEN not in repr(describe(view))
  words = describe(view)
  assert (words.address, words.port, words.token, words.toggle_value) == (HOST, '9000', f'set, {len(TOKEN)} characters', 'routes via the X10')


@pytest.mark.parametrize('content, note, address', [
  ({'enabled': True}, 'auto', 'auto discovery'),
  ({'enabled': True, 'host': 'auto'}, 'auto', 'auto discovery'),
  ({'enabled': True, 'host': ' Auto '}, 'auto', 'auto discovery'),
  ({'enabled': True, 'host': '8.8.8.8'}, 'invalid', 'not valid'),
  ({'enabled': True, 'host': 'x10.local'}, 'invalid', 'not valid'),
  ({'enabled': True, 'host': 123}, 'invalid', 'not valid'),
  ({'enabled': True, 'host': ' 192.168.1.5'}, 'invalid', 'not valid'),
])
def test_hosts_that_are_not_a_fixed_lan_address_are_explained(env, content, note, address):
  env.put(content)
  view = env.flow.update()
  assert (view.host, view.host_note, describe(view).address) == ('', note, address) and not view.can_enable


def test_the_view_follows_what_the_readers_see(env):
  for content in ({'port': '9001'}, {'port': 'abc'}, {'port': 70000}, {'port': True}, {'enabled': 1}, {'enabled': 'true'}):
    env.put(content)
    env.tick(READ_INTERVAL_S)
    view = env.flow.update()
    assert view.port == config.port_of(content) and view.enabled == (content.get('enabled') is True), content


@pytest.mark.parametrize('token, length, problem', [
  ('x' * 9, 9, 'at least 10'), ('x' * 10, 10, ''), ('x' * 128, 128, ''), ('x' * 129, 129, 'at most 128'),
  ('has space 1234', 14, 'printable ASCII'), (12345, 1, 'must be text'), ('', 0, ''),
])
def test_a_saved_token_is_summarized_by_its_length_and_problem(env, token, length, problem):
  env.put({'token': token})
  view = env.flow.update()
  assert view.token_length == length and (problem in view.token_problem if problem else not view.token_problem), view
  if view.token_length and view.token_problem:
    assert describe(view).token == 'needs fixing' and not view.has_token


def test_a_damaged_file_is_reported_and_other_cards_stay_neutral(env):
  env.path.write_text('{broken')
  view = env.flow.update()
  words = describe(view)
  assert view.document == config.DOC_CORRUPT and not view.readable and not view.can_enable
  assert words.status_title == 'Settings file damaged' and 'backup' in words.status_detail


def test_an_unreadable_file_is_reported(env):
  env.path.mkdir()
  view = env.flow.update()
  assert view.document == config.DOC_UNREADABLE and describe(view).status_title == 'Settings file unreadable'


def test_changes_made_over_ssh_show_up_within_half_a_second(env):
  env.put({'host': HOST})
  assert env.flow.update().host == HOST
  env.put({'host': '10.0.0.7'})
  assert env.flow.update().host == HOST   # looked at it a moment ago
  env.tick(READ_INTERVAL_S)
  assert env.flow.update().host == '10.0.0.7'


# ---------------------------------------------------------------- edits

def test_text_is_checked_on_the_ui_thread_and_a_refusal_writes_nothing(env):
  flow = env.flow
  for call, text in ((flow.set_host, '8.8.8.8'), (flow.set_host, 'auto'), (flow.set_host, ''), (flow.set_port, '0'), (flow.set_port, '65536'),
                     (flow.set_port, 'abc'), (flow.set_token, 'short'), (flow.set_token, 'has space 1234'), (flow.set_token, 'x' * 129)):
    assert call(text) is False
    assert flow.take_notice(), text
    assert flow.take_notice() == ''  # a notice is shown once
  assert env.executor.jobs == [] and not env.path.exists()


def test_an_edit_is_written_in_the_background_and_shows_up_in_the_next_frame(env):
  assert env.flow.set_host(f' {HOST} ') is True
  assert not env.path.exists() and len(env.executor.pending()) == 1  # nothing happened on the calling thread
  assert env.flow.update().busy is True
  view = env.settle()
  assert (view.busy, view.host) == (False, HOST) and env.disk() == {'host': HOST}
  assert env.flow.take_notice() == ''


def test_one_edit_at_a_time(env):
  assert env.flow.set_host(HOST) is True
  assert env.flow.set_port('9000') is False and env.flow.take_notice() == BUSY_NOTICE
  env.settle()
  assert env.flow.set_port('9000') is True
  env.settle()
  assert env.disk() == {'host': HOST, 'port': 9000}


def test_nothing_can_be_edited_while_driving(env):
  env.allowed[0] = False
  assert env.flow.update().editable is False
  for call in (lambda: env.flow.set_enabled(True), lambda: env.flow.set_host(HOST), lambda: env.flow.set_port('9000'),
               lambda: env.flow.set_token(TOKEN), env.flow.clear):
    assert call() is False and env.flow.take_notice() == LOCKED_NOTICE
  assert env.executor.jobs == [] and not env.path.exists()
  env.allowed[0] = True
  assert env.flow.update().editable is True and env.flow.set_host(HOST) is True


def test_the_writers_refusal_comes_back_as_a_notice_and_the_switch_stays_off(env):
  assert env.flow.set_enabled(True) is True
  view = env.settle()
  assert not view.enabled and not env.path.exists()
  assert 'address' in env.flow.take_notice()
  env.put({'host': HOST})
  env.tick(READ_INTERVAL_S)
  env.flow.update()
  env.flow.set_enabled(True)
  env.settle()
  assert 'token' in env.flow.take_notice() and env.disk() == {'host': HOST}


def test_the_whole_setup_works_and_the_readers_follow(env, monkeypatch):
  monkeypatch.setenv(config.CONFIG_ENV, str(env.path))
  for call in (lambda: env.flow.set_host(HOST), lambda: env.flow.set_port('9000'), lambda: env.flow.set_token(TOKEN),
               lambda: env.flow.set_enabled(True)):
    assert call() is True
    env.settle()
    assert env.flow.take_notice() == ''
    env.tick(0.1)
  assert env.disk() == {'enabled': True, 'host': HOST, 'port': 9000, 'token': TOKEN}
  assert stat.S_IMODE(os.stat(env.path).st_mode) == 0o600
  monkeypatch.setattr(config, '_cache', (None, {}))
  assert config.sidecar_enabled() and config.resolve_route_server() == config.RouteServer(HOST, 9000, config.SOURCE_FIXED)
  view = env.flow.update()
  assert view.can_enable and view.enabled and describe(view).toggle_value == 'routes via the X10'


def test_clear_turns_it_off_and_forgets_the_token_but_keeps_the_address(env):
  env.put({'enabled': True, 'host': HOST, 'port': 9000, 'token': TOKEN})
  assert env.flow.clear() is True
  view = env.settle()
  assert env.disk() == {'enabled': False, 'host': HOST, 'port': 9000} and not view.enabled and view.token_length == 0


def test_clear_also_repairs_a_damaged_file_and_keeps_a_backup(env):
  env.path.write_bytes(b'{"enabled": tru')
  assert env.flow.clear() is True
  view = env.settle()
  assert env.disk() == {'enabled': False} and view.document == config.DOC_OK
  backups = [name for name in os.listdir(env.path.parent) if name.startswith('sidecar.json.bak-')]
  assert len(backups) == 1 and (env.path.parent / backups[0]).read_bytes() == b'{"enabled": tru'


def test_an_edit_to_a_damaged_file_is_refused_with_the_reason(env):
  env.path.write_text('nonsense')
  env.flow.set_host(HOST)
  env.settle()
  assert 'damaged' in env.flow.take_notice() and env.path.read_text() == 'nonsense'


def test_an_unexpected_worker_error_becomes_a_plain_notice(env, monkeypatch):
  def boom(*_args, **_kwargs):
    raise RuntimeError('secret detail ' + TOKEN)

  monkeypatch.setattr(config, 'save_config', boom)
  env.flow.set_host(HOST)
  view = env.settle()
  notice = env.flow.take_notice()
  assert not view.busy and notice == 'Could not save the X10 settings' and TOKEN not in notice


def test_an_unchanged_save_is_silent(env):
  env.put({'host': HOST})
  env.flow.set_host(HOST)
  env.settle()
  assert env.flow.take_notice() == ''


# ---------------------------------------------------------------- the X10 check

def test_the_x10_is_checked_only_while_the_screen_is_open_and_an_address_is_saved(env):
  env.flow.update()
  assert env.checks == [] and env.executor.jobs == []        # not shown
  env.flow.activate()
  env.flow.update()
  assert env.checks == [] and env.executor.jobs == []        # nothing to ask yet
  env.put({'host': HOST, 'port': 9000})
  env.tick(READ_INTERVAL_S)
  assert env.flow.update().checking is True
  assert len(env.executor.pending()) == 1 and env.checks == []   # queued, not run on this thread
  view = env.settle()
  assert env.checks == [(HOST, 9000)] and view.status == READY._replace(port=9000) and not view.checking
  env.flow.deactivate()
  env.tick(POLL_INTERVAL_S * 3)
  env.flow.update()
  assert len(env.executor.jobs) == 1                        # closed again: no more traffic


def test_the_check_repeats_every_poll_interval_and_the_age_counts_up(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  view = env.settle()
  assert view.status_age_s == 0 and describe(view).status_detail == 'app 1.4.2, up 2 h\nchecked just now'
  env.tick(POLL_INTERVAL_S - 0.5)
  view = env.flow.update()
  assert len(env.executor.jobs) == 1 and view.status_age_s == POLL_INTERVAL_S - 0.5
  assert describe(view).status_detail.endswith('checked 4 s ago')
  env.tick(0.6)
  env.flow.update()
  assert len(env.executor.jobs) == 2
  env.answer[0] = DEAD
  view = env.settle()
  assert view.status == DEAD and describe(view).status_title == 'X10 not reachable' and describe(view).test == 'not reachable'


def test_one_check_at_a_time_even_when_the_x10_answers_slowly(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  env.tick(POLL_INTERVAL_S * 4)
  assert env.flow.update().checking is True and len(env.executor.jobs) == 1  # the first one has not finished
  env.settle()
  assert len(env.executor.jobs) == 2  # it is long overdue, so the next one starts as soon as the first is collected


def test_test_now_asks_again_without_waiting_for_the_interval(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  env.settle()
  env.tick(1)
  assert env.flow.test_now() is True
  assert env.flow.update().checking is True and len(env.executor.jobs) == 2
  env.settle()
  assert len(env.checks) == 2


def test_test_now_without_an_address_says_so(env):
  assert env.flow.test_now() is False and 'address' in env.flow.take_notice()


def test_a_changed_address_drops_the_old_answer_and_checks_the_new_one(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  assert env.settle().status == READY
  env.put({'host': '10.0.0.7'})
  env.tick(READ_INTERVAL_S)
  view = env.flow.update()
  assert view.status is None and view.host == '10.0.0.7' and view.checking  # the old answer says nothing about the new address
  view = env.settle()
  assert env.checks[-1] == ('10.0.0.7', 8765) and view.status.host == '10.0.0.7'


def test_an_answer_that_arrives_after_the_settings_changed_is_discarded_and_asked_again(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()                                              # a check for HOST is queued
  env.put({'host': '10.0.0.7'})
  env.tick(READ_INTERVAL_S)
  env.flow.update()
  env.executor.run()                                             # the old check finishes now, with the old address
  view = env.flow.update()
  assert view.status is None and view.checking                   # discarded; the new address is being asked
  view = env.settle()
  assert [call[0] for call in env.checks] == [HOST, '10.0.0.7'] and view.status.host == '10.0.0.7'


def test_saving_an_edit_asks_the_x10_again_right_away(env):
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  env.settle()
  env.flow.set_port('9100')
  env.tick(1)
  env.executor.run_all()                                         # the save
  assert env.flow.update().status is None                        # the old answer was for port 8765
  view = env.settle()                                            # the check of the new port
  assert env.checks[-1] == (HOST, 9100) and view.port == 9100 and view.status.port == 9100


def test_a_failing_check_does_not_break_the_screen(env):
  def explode(host, port):
    raise RuntimeError('boom')

  env.flow = SidecarFlow(lambda: True, path=str(env.path), check=explode, now=lambda: env.clock[0], executor=env.executor)
  env.put({'host': HOST})
  env.flow.activate()
  env.flow.update()
  view = env.settle()
  assert view.status is None and not view.checking
  env.tick(POLL_INTERVAL_S)
  assert env.flow.update().checking is True


def test_the_screen_thread_never_waits_for_a_slow_x10_or_a_slow_disk(tmp_path):
  release = threading.Event()
  started = threading.Event()

  def slow_check(host, port):
    started.set()
    release.wait(5)
    return READY._replace(host=host, port=port)

  path = tmp_path / 'sidecar.json'
  flow = SidecarFlow(lambda: True, path=str(path), check=slow_check)
  try:
    path.write_text(json.dumps({'host': HOST}))
    flow.activate()
    began = time.monotonic()
    first = flow.update()
    flow.set_port('9000')
    second = flow.update()
    for _ in range(50):
      flow.update()
    assert time.monotonic() - began < 0.5
    assert first.checking is True and started.wait(2) and second.checking is True
    assert second.status is None
    release.set()
    deadline = time.monotonic() + 3
    view = flow.update()
    while (view.status is None or view.busy) and time.monotonic() < deadline:
      time.sleep(0.01)
      view = flow.update()
    assert view.status == READY._replace(port=9000) and not view.busy and view.port == 9000
  finally:
    release.set()
    flow.close()


def test_close_shuts_the_workers_down_and_nothing_runs_afterwards(tmp_path):
  path = tmp_path / 'sidecar.json'
  path.write_text(json.dumps({'host': HOST}))
  ran = []
  flow = SidecarFlow(lambda: True, path=str(path), check=lambda host, port: ran.append(host) or READY)  # runs on a worker thread
  flow.activate()
  flow.update()
  assert flow._executor is not None
  flow.close()
  assert flow._executor is None
  flow.update()
  assert flow.set_host(HOST) is False
  flow.activate()
  flow.update()
  assert flow._executor is None and flow.take_notice() == ''


def test_an_executor_handed_in_is_not_shut_down_by_close(env):
  env.flow.close()
  assert env.executor.shutdown_calls == 0


# ---------------------------------------------------------------- showing the token on request

def test_reveal_token_reads_the_file_only_when_asked_and_shows_printable_ascii_only(env):
  env.put({'token': TOKEN})
  assert env.flow.reveal_token() == TOKEN
  env.put({'token': 'abあ\u0007c\x7f' + 'z' * 200})
  revealed = env.flow.reveal_token()
  assert revealed.startswith('ab??c?z') and len(revealed) == config.TOKEN_MAX_LENGTH and revealed.isascii() and revealed.isprintable()
  for content in ({}, {'token': 5}, {'token': None}):
    env.put(content)
    assert env.flow.reveal_token() == ''
  env.path.unlink()
  assert env.flow.reveal_token() == ''


def test_no_view_text_or_notice_ever_contains_the_token(env):
  marker = 'ZZ-never-show-ZZ-0123456789'
  env.put({'enabled': True, 'host': HOST, 'token': marker})
  seen = []
  env.flow.activate()
  for call in (env.flow.update, lambda: env.flow.set_token(marker + ' bad'), lambda: env.flow.set_token('x'), env.flow.update, env.settle):
    result = call()
    seen.append(repr(result))
  seen.append(env.flow.take_notice())
  seen.append(repr(describe(env.flow.update())))
  assert all(marker not in text for text in seen)


# ---------------------------------------------------------------- words

def test_every_card_has_words_for_every_state(env):
  states = [SidecarView(), SidecarView(document=config.DOC_CORRUPT), SidecarView(document=config.DOC_UNREADABLE),
            SidecarView(document=config.DOC_OK, enabled=True, host_note='auto'), SidecarView(document=config.DOC_OK, host_note='invalid'),
            SidecarView(document=config.DOC_OK, host=HOST, token_length=24), SidecarView(document=config.DOC_OK, host=HOST, token_length=5,
                                                                                         token_problem='short', enabled=True),
            SidecarView(document=config.DOC_OK, host=HOST, token_length=24, status=READY, status_age_s=3),
            SidecarView(document=config.DOC_OK, host=HOST, token_length=24, status=DEAD, status_age_s=70, enabled=True),
            SidecarView(document=config.DOC_OK, host=HOST, token_length=24, checking=True),
            SidecarView(document=config.DOC_OK, host=HOST, token_length=24, checking=True, status=READY, status_age_s=1),
            SidecarView(editable=False), SidecarView(document=config.DOC_OK, host=HOST, port=9000, token_length=24, editable=False)]
  for view in states:
    words = describe(view)
    for text in (words.status_title, words.status_detail, words.toggle_value, words.address, words.port, words.token, words.test):
      assert isinstance(text, str) and text, (view, words)
    assert words.status_title[0].isupper() or words.status_title[0].isdigit()
  assert describe(SidecarView(editable=False)).warning == 'Park the car to change the X10 settings.'
  assert describe(states[6]).warning == 'short.' and describe(states[7]).warning == ''
  assert describe(states[9]).status_title == 'Checking the X10' and describe(states[9]).test == 'checking'
  assert describe(states[10]).test == 'checking again'
  assert describe(states[3]).status_detail.startswith('Auto discovery is in use')
  assert describe(states[8]).status_detail == 'connection refused\nchecked 70 s ago'


@pytest.mark.parametrize('seconds, text', [(-5, 'just now'), (0, 'just now'), (2.9, 'just now'), (3, '3 s ago'), (89, '89 s ago'), (90, '2 min ago'),
                                          (3000, '50 min ago'), (5400, '2 h ago'), (86400, '24 h ago')])
def test_age_in_one_short_unit(seconds, text):
  assert age_text(seconds) == text


def test_the_token_is_grouped_by_fours_for_comparing_with_the_x10_app():
  assert group_token('abcdefghij') == 'abcd efgh ij' and group_token('abcd') == 'abcd' and group_token('') == ''
  assert group_token('a' * 128).count(' ') == 31
  assert sidecar_flow.hide_text('abc') == '•••' and sidecar_flow.hide_text('') == ''


def test_the_flow_reads_the_default_location_when_no_path_is_given(tmp_path, monkeypatch):
  target = tmp_path / 'elsewhere.json'
  target.write_text(json.dumps({'host': HOST}))
  monkeypatch.setenv(config.CONFIG_ENV, str(target))
  flow = SidecarFlow(lambda: True, executor=ManualExecutor())
  assert flow.update().host == HOST and Path(config.config_path()) == target
