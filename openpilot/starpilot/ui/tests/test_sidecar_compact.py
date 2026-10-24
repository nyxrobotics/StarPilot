"""The compact X10 sidecar page: what each card shows, what a tap does, and the keyboard's live check line."""

import hashlib
import json
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import Mock

import pyray as rl
import pytest

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.sidecar_status import Verdict, X10Status
from openpilot.starpilot.ui import runtime_app, sidecar_compact
from openpilot.starpilot.ui.presentation import Profile
from openpilot.starpilot.ui.settings_state import COMPACT_MENU, Destination, SettingsInput, SettingsState
from openpilot.starpilot.ui.sidecar_compact import SidecarCompact, SidecarInputDialog, SidecarPage
from openpilot.starpilot.ui.sidecar_flow import SidecarView, describe, host_feedback, port_feedback, token_feedback

HOST = '192.168.1.20'
READY = X10Status(Verdict.READY, HOST, 8765, 'app 1.4.2, up 2 h', api=1, version='1.4.2', engine='ready', uptime_s=7200, http_status=200)
CONFIGURED = SidecarView(document=config.DOC_OK, enabled=True, host=HOST, port=9000, token_length=24, status=READY, status_age_s=3.0)


# ---------------------------------------------------------------- stand-ins for the widgets and the flow

class Card:
  """Records what the page does to a BigButton / GreyBigButton / BigToggle."""

  def __init__(self, text='', value='', *args, **kwargs):
    self.text, self.value, self.args, self.kwargs = text, value, args, kwargs
    self.enabled, self.visible, self.checked, self.click = True, True, False, None
    self.writes = 0

  def get_text(self):
    return self.text

  def set_text(self, text):
    self.text = text
    self.writes += 1

  def get_value(self):
    return self.value

  def set_value(self, value):
    self.value = value
    self.writes += 1

  def set_enabled(self, enabled):
    self.enabled = enabled

  def set_visible(self, visible):
    self.visible = visible

  def set_click_callback(self, callback):
    self.click = callback

  def set_checked(self, checked):
    self.checked = checked

  @property
  def is_enabled(self):
    return self.enabled() if callable(self.enabled) else self.enabled

  @property
  def is_visible(self):
    return self.visible() if callable(self.visible) else self.visible


class Recorder:
  """A dialog class that remembers how it was built."""

  def __init__(self, label, built):
    self.label, self.built = label, built

  def __call__(self, *args, **kwargs):
    self.built.append((self.label, args, kwargs))
    return (self.label, args, kwargs)


class FlowStub:
  def __init__(self, view=CONFIGURED):
    self.view, self.notice, self.token, self.calls = view, '', 'abcd' * 10, []

  def activate(self):
    self.calls.append('activate')

  def deactivate(self):
    self.calls.append('deactivate')

  def update(self):
    self.calls.append('update')
    return self.view

  def take_notice(self):
    notice, self.notice = self.notice, ''
    return notice

  def reveal_token(self):
    return self.token

  def test_now(self):
    self.calls.append('test_now')
    return True

  def set_enabled(self, enabled):
    self.calls.append(('enabled', enabled))
    return True

  def set_host(self, text):
    self.calls.append(('host', text))
    return True

  def set_port(self, text):
    self.calls.append(('port', text))
    return True

  def set_token(self, text):
    self.calls.append(('token', text))
    return True

  def clear(self):
    self.calls.append('clear')
    return True


@pytest.fixture
def made(monkeypatch):
  """A SidecarPage built from stand-in widgets, with the pushed widgets and built dialogs recorded."""
  pushed, built = [], []

  def fake_init(self, **_kwargs):
    namespace = NS(added=[])
    namespace.add_widgets = namespace.added.extend
    self._scroller = namespace

  for name in ('show_event', 'hide_event', '_update_state'):
    monkeypatch.setattr(sidecar_compact.NavScroller, name, lambda self: None)
  monkeypatch.setattr(sidecar_compact.NavScroller, '__init__', fake_init)
  for name in ('BigButton', 'GreyBigButton', 'BigToggle'):
    monkeypatch.setattr(sidecar_compact, name, Card)
  for name in ('SidecarInputDialog', 'BigDialog', 'BigConfirmationDialog', 'SettingDescriptionDialog'):
    monkeypatch.setattr(sidecar_compact, name, Recorder(name, built))
  monkeypatch.setattr(sidecar_compact, 'gui_app', NS(push_widget=pushed.append, texture=lambda *args: 'icon'))
  flow = FlowStub()
  page = SidecarPage(flow)
  cards = dict(zip(('status', 'warning', 'toggle', 'address', 'token', 'port', 'reveal', 'test', 'clear'), page._scroller.added, strict=True))
  return NS(page=page, flow=flow, cards=cards, pushed=pushed, built=built)


# ---------------------------------------------------------------- the cards

def test_the_cards_come_in_the_order_a_person_sets_things_up(made):
  assert [card.text for card in made.page._scroller.added] == \
         ['x10 sidecar', '', 'use the x10', 'x10 address', 'token', 'port', 'show token', 'test connection', 'clear']
  assert made.cards['toggle'].kwargs['description'].startswith('Use the X10 head unit')
  for name in ('address', 'token', 'port', 'reveal', 'test', 'clear'):
    assert made.cards[name].kwargs['description'], name  # a long press explains every card


def test_apply_shows_the_view_with_lowercase_titles(made):
  made.page.apply(CONFIGURED)
  cards = made.cards
  assert (cards['status'].text, cards['status'].value) == ('x10 ready', 'app 1.4.2, up 2 h\nchecked 3 s ago')
  assert (cards['toggle'].value, cards['toggle'].checked) == ('routes via the x10', True)
  assert (cards['address'].value, cards['port'].value, cards['token'].value) == (HOST, '9000', 'set, 24 characters')
  assert cards['test'].value == 'ready'
  made.page.apply(SidecarView(document=config.DOC_OK, enabled=False, host_note='auto', token_length=24, token_problem='x'))
  assert (cards['status'].text, cards['address'].value, cards['token'].value, cards['toggle'].checked) == \
         ('x10 address not set', 'auto discovery', 'needs fixing', False)


def test_only_a_card_whose_words_changed_is_touched(made):
  made.page.apply(CONFIGURED)
  writes = {name: card.writes for name, card in made.cards.items()}
  made.page.apply(CONFIGURED)
  assert {name: card.writes for name, card in made.cards.items()} == writes  # re-measuring text every frame would be wasteful
  made.page.apply(replace(CONFIGURED, status_age_s=4.0))
  assert made.cards['status'].writes == writes['status'] + 1 and made.cards['address'].writes == writes['address']


def test_the_warning_and_show_token_cards_appear_only_when_they_have_something_to_say(made):
  made.page.apply(CONFIGURED)
  assert not made.cards['warning'].is_visible and made.cards['reveal'].is_visible
  made.page.apply(SidecarView(document=config.DOC_OK, host=HOST, token_length=5, token_problem='The token needs at least 10 characters (now 5)'))
  assert made.cards['warning'].is_visible and made.cards['warning'].value.startswith('The token needs at least 10')
  made.page.apply(SidecarView(document=config.DOC_MISSING))
  assert not made.cards['reveal'].is_visible  # there is no token to show
  made.page.apply(SidecarView(editable=False))
  assert made.cards['warning'].is_visible and made.cards['warning'].value == 'Park the car to change the X10 settings.'


def test_cards_that_change_settings_are_locked_while_driving_or_saving_and_the_check_is_not(made):
  for view, editable in ((CONFIGURED, True), (replace(CONFIGURED, editable=False), False),
                         (replace(CONFIGURED, busy=True), False)):
    made.page.apply(view)
    for name in ('toggle', 'address', 'token', 'port', 'clear'):
      assert made.cards[name].is_enabled is editable, (name, view)
    assert made.cards['test'].is_enabled and made.cards['status'].is_enabled
    assert made.cards['reveal'].is_enabled is view.editable  # the token is shown only while parked, even while a change is saved


def test_clear_is_offered_only_when_there_is_something_to_clear(made):
  for view, offered in ((CONFIGURED, True), (SidecarView(document=config.DOC_MISSING), False),
                        (SidecarView(document=config.DOC_OK, host=HOST, port=9000), False), (SidecarView(document=config.DOC_CORRUPT), True)):
    made.page.apply(view)
    assert made.cards['clear'].is_enabled is offered, view
  made.page.apply(SidecarView(document=config.DOC_MISSING))
  made.cards['clear'].click()
  assert made.built == []


def test_the_switch_asks_the_flow_and_falls_back_to_the_saved_state(made):
  made.cards['toggle'].kwargs['toggle_callback'](True)
  assert made.flow.calls == [('enabled', True)]
  made.page.apply(SidecarView(document=config.DOC_OK, enabled=False, busy=True))
  made.cards['toggle'].checked = True            # the user's tap flipped the pill
  made.page.apply(SidecarView(document=config.DOC_OK, enabled=False, busy=True))
  assert made.cards['toggle'].checked is True    # while the change is being written the pill keeps the choice
  made.page.apply(SidecarView(document=config.DOC_OK, enabled=False, busy=False))
  assert made.cards['toggle'].checked is False   # a refused change flips it back


# ---------------------------------------------------------------- taps

def test_the_address_card_opens_the_keyboard_on_digits_with_the_saved_address(made):
  made.page.apply(CONFIGURED)
  made.cards['address'].click()
  [(label, args, kwargs)] = made.built
  assert (label, args) == ('SidecarInputDialog', ('x10 address', HOST))
  assert kwargs['feedback'] is host_feedback and kwargs['numeric'] is True and not kwargs.get('secret')
  kwargs['confirm_callback']('192.168.1.99')
  assert made.flow.calls[-1] == ('host', '192.168.1.99') and len(made.pushed) == 1


def test_the_port_card_opens_the_keyboard_on_digits_with_the_saved_port(made):
  made.page.apply(CONFIGURED)
  made.cards['port'].click()
  [(label, args, kwargs)] = made.built
  assert (label, args) == ('SidecarInputDialog', ('port', '9000'))
  assert kwargs['feedback'] is port_feedback and kwargs['numeric'] is True
  kwargs['confirm_callback']('9100')
  assert made.flow.calls[-1] == ('port', '9100')


def test_the_token_card_opens_an_empty_masked_keyboard_and_never_prefills_the_token(made):
  made.page.apply(CONFIGURED)
  made.cards['token'].click()
  [(label, args, kwargs)] = made.built
  assert (label, args) == ('SidecarInputDialog', ('token (10-128)', ''))
  assert kwargs['feedback'] is token_feedback and kwargs['secret'] is True and not kwargs.get('numeric')
  kwargs['confirm_callback']('0123456789abcdef')
  assert made.flow.calls[-1] == ('token', '0123456789abcdef')
  assert 'reveal_token' not in made.flow.calls


def test_test_connection_asks_the_flow_to_check_now(made):
  made.cards['test'].click()
  assert made.flow.calls == ['test_now']


def test_clear_needs_a_slide_to_confirm_and_only_while_parked(made):
  made.page.apply(CONFIGURED)
  made.cards['clear'].click()
  [(label, args, kwargs)] = made.built
  assert label == 'BigConfirmationDialog' and args[0] == 'slide to clear' and kwargs == {'red': True}
  args[2]()
  assert made.flow.calls[-1] == 'clear'
  made.built.clear()
  made.page.apply(replace(CONFIGURED, editable=False))
  made.cards['clear'].click()
  assert made.built == []


def test_show_token_lists_it_in_groups_of_four_on_lines_of_sixteen(made):
  made.flow.token = 'abcdefghijklmnopqrstuvwxyz0123456789'
  made.cards['reveal'].click()
  [(label, args, _kwargs)] = made.built
  assert label == 'SettingDescriptionDialog' and args[0] == 'token'
  assert args[1].split('\n') == ['abcd efgh ijkl mnop', 'qrst uvwx yz01 2345', '6789']
  assert args[1].replace(' ', '').replace('\n', '') == made.flow.token


def test_show_token_does_nothing_while_driving(made):
  made.page.apply(replace(CONFIGURED, editable=False))
  made.cards['reveal'].click()
  assert made.built == []


def test_show_token_says_so_when_there_is_none(made):
  made.flow.token = ''
  made.cards['reveal'].click()
  assert made.built == [('BigDialog', ('', 'No token is saved.'), {})]


def test_a_notice_is_shown_as_one_message_per_event(made):
  made.flow.notice = 'The port must be a number from 1 to 65535'
  made.page._update_state()
  assert made.built == [('BigDialog', ('', 'The port must be a number from 1 to 65535'), {})]
  made.page._update_state()
  assert len(made.built) == 1 and made.flow.calls.count('update') == 2


def test_the_flow_runs_only_while_the_page_is_shown(made):
  made.page.show_event()
  made.page.hide_event()
  assert made.flow.calls == ['activate', 'deactivate']


# ---------------------------------------------------------------- the keyboard dialog

def dialog(*, secret=False, numeric=False, text='', feedback=token_feedback):
  """A SidecarInputDialog without the graphics resources the base class loads."""
  widget = SidecarInputDialog.__new__(SidecarInputDialog)
  widget._feedback, widget._secret, widget._numbers_pending = feedback, secret, numeric
  widget._password_mode = secret
  object.__setattr__(widget, '_keyboard', NS(text=lambda: text, _initialized=False, _set_keys=Mock(), _special_keys='digits'))
  widget._message = Mock()
  widget._rect = rl.Rectangle(0, 0, 536, 240)
  widget._top_left_button_rect = rl.Rectangle(0, 0, 106, 170)
  widget._top_right_button_rect = rl.Rectangle(430, 0, 106, 170)
  widget._dragging_down = widget._playing_dismiss_animation = False
  return widget


def test_a_secret_can_be_shown_and_hidden_by_tapping_the_text(monkeypatch):
  presses = []
  monkeypatch.setattr(sidecar_compact.BigInputDialog, '_handle_mouse_press', lambda self, pos: presses.append(pos))
  widget = dialog(secret=True, text='abc')
  assert widget._password_mode and not widget.revealing
  area = widget._reveal_rect()
  assert (area.x, area.y, area.width, area.height) == (106, 0, 324, 170)
  widget._handle_mouse_press(rl.Vector2(250, 30))
  assert widget.revealing and not widget._password_mode
  widget._handle_mouse_press(rl.Vector2(250, 30))
  assert not widget.revealing and widget._password_mode
  for outside in (rl.Vector2(40, 30), rl.Vector2(480, 30), rl.Vector2(250, 200)):  # the check mark, backspace and the keyboard
    widget._handle_mouse_press(outside)
  assert widget._password_mode and len(presses) == 5  # the base class still saw every press


def test_nothing_else_can_be_revealed_and_nothing_is_toggled_while_the_dialog_leaves(monkeypatch):
  monkeypatch.setattr(sidecar_compact.BigInputDialog, '_handle_mouse_press', lambda self, pos: None)
  plain = dialog(secret=False, text='192.168.1.2', feedback=host_feedback)
  plain._handle_mouse_press(rl.Vector2(250, 30))
  plain.toggle_reveal()
  assert not plain._password_mode and not plain.revealing
  leaving = dialog(secret=True, text='abc')
  leaving._playing_dismiss_animation = True
  leaving._handle_mouse_press(rl.Vector2(250, 30))
  assert leaving._password_mode


@pytest.mark.parametrize('secret, text, revealed, shown, error', [
  (False, '', False, 'e.g. 192.168.1.20', False),
  (False, '8.8.8.8', False, 'must be a private address', True),
  (True, '', False, 'tap the text to show it', False),
  (True, '', True, 'tap the text to hide it', False),
  (True, 'abc', False, 'need 10 characters (3)', True),
  (True, 'x' * 24, False, '24 characters, tap text to show', False),
  (True, 'x' * 24, True, '24 characters, tap text to hide', False),
])
def test_the_check_line_adds_the_show_hide_hint_only_for_a_secret_without_an_error(secret, text, revealed, shown, error):
  widget = dialog(secret=secret, text=text, feedback=token_feedback if secret else host_feedback)
  if revealed:
    widget.toggle_reveal()
  result = widget.message()
  assert (result.text, result.error) == (shown, error)
  assert len(result.text) <= 36  # the line is 330 px wide at 22 px


def test_the_check_line_is_drawn_under_the_text_in_orange_for_an_error_and_grey_otherwise(monkeypatch):
  monkeypatch.setattr(sidecar_compact.BigInputDialog, '_render', lambda self, _: None)
  widget = dialog(secret=True, text='abc')
  widget._render(None)
  widget._message.set_text.assert_called_once_with('need 10 characters (3)')
  widget._message.set_text_color.assert_called_once_with(sidecar_compact.ORANGE)
  area = widget._message.render.call_args.args[0]
  assert (area.x, area.y, area.width, area.height) == (106, 47, 330, 24)  # below the 70 px keyboard top, left-aligned with the text
  widget = dialog(secret=True, text='x' * 24)
  widget._render(None)
  widget._message.set_text_color.assert_called_once_with(sidecar_compact.GREY)


def test_the_number_keys_replace_the_letters_only_after_the_keys_were_laid_out_once(monkeypatch):
  monkeypatch.setattr(sidecar_compact.BigInputDialog, '_render', lambda self, _: None)
  widget = dialog(numeric=True, text='192', feedback=host_feedback)
  widget._render(None)
  widget._keyboard._set_keys.assert_not_called()  # switching earlier would give the digit keys the wrong touch positions
  widget._keyboard._initialized = True
  widget._render(None)
  widget._keyboard._set_keys.assert_called_once_with('digits')
  widget._render(None)
  widget._keyboard._set_keys.assert_called_once_with('digits')  # once: a person who switched back to letters stays there
  letters = dialog(numeric=False, text='x')
  letters._keyboard._initialized = True
  letters._render(None)
  letters._keyboard._set_keys.assert_not_called()


# ---------------------------------------------------------------- where the screen lives

def test_the_compact_menu_has_one_x10_card_after_galaxy_with_a_reviewed_icon():
  index = [item[0] for item in COMPACT_MENU].index(Destination.X10)
  assert COMPACT_MENU[index - 1][0] == Destination.GALAXY and COMPACT_MENU[index][1] == 'X10 sidecar'
  assert [item[0] for item in COMPACT_MENU].count(Destination.X10) == 1 and Destination.X10.value == 'x10'
  root = Path(__file__).parents[3]
  manifest = {entry['file']: entry for entry in json.loads((root / 'starpilot/ui/settings-assets.json').read_text())['files']}
  for _destination, label, filename, width, height in COMPACT_MENU:
    if filename is None:
      continue
    data = (root / 'selfdrive/assets' / filename).read_bytes()
    assert filename in manifest, f'{label}: {filename} is not a reviewed Settings image'  # SettingsAssets.image refuses it otherwise
    assert (manifest[filename]['bytes'], manifest[filename]['sha256']) == (len(data), hashlib.sha256(data).hexdigest())
    assert 0 < width <= 4096 and 0 < height <= 4096


def test_tapping_the_x10_card_requests_its_destination():
  emitted = []
  touch = SettingsInput(Profile.COMPACT, emitted.append)
  index = next(index for index, item in enumerate(COMPACT_MENU) if item[0] == Destination.X10)
  state = SettingsState(compact_scroll_x=-422 * index)
  touch.press(45, 75, state)
  touch.release(45, 75, state)
  assert emitted[-1].destination.destination == Destination.X10


def test_the_x10_destination_opens_the_page_of_the_session_flow(monkeypatch):
  layout = runtime_app.StarMiciMainLayout.__new__(runtime_app.StarMiciMainLayout)
  flow = FlowStub()
  unavailable = Mock()
  monkeypatch.setattr(layout, 'star', NS(sidecar_flow=flow, _unavailable=unavailable), raising=False)
  opened = []
  monkeypatch.setattr(sidecar_compact.SidecarCompact, 'open', lambda self: opened.append(self.flow))
  layout._open_compact_destination(Destination.X10)
  assert opened == [flow] and unavailable.call_count == 0


def test_the_compact_entry_pushes_a_page_built_for_its_flow(monkeypatch):
  pushed = []
  monkeypatch.setattr(sidecar_compact, 'gui_app', NS(push_widget=pushed.append))
  monkeypatch.setattr(sidecar_compact, 'SidecarPage', lambda flow: ('page', flow))
  flow = FlowStub()
  SidecarCompact(flow).open()
  assert pushed == [('page', flow)]


def test_the_session_closes_its_flow_with_the_other_owners():
  session = runtime_app.StarShellSession.__new__(runtime_app.StarShellSession)
  closed = []
  for name, owner in (('sidecar_flow', NS(close=lambda: closed.append('sidecar'))), ('galaxy_flow', NS(close=lambda: closed.append('galaxy'))),
                      ('drive_state', NS(physical=NS(close=lambda: None))), ('bluetooth_source', None), ('model_source', None),
                      ('map_source', None), ('pip_warning', NS(close=lambda: None)), ('pip_renderer', NS(close=lambda: None)),
                      ('view', NS(close=lambda: None)), ('fonts', NS(close=lambda: None))):
    setattr(session, name, owner)
  session.close()
  assert closed == ['galaxy', 'sidecar']


def test_the_words_the_page_shows_are_the_words_describe_returns():
  words = describe(CONFIGURED)
  assert words.status_title == 'X10 ready' and words.toggle_value == 'routes via the X10' and words.test == 'ready'
