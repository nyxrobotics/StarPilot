"""The large (C3) X10 sidecar page: the same flow and words as the compact page, laid out for the 2160x1080 screen."""

from dataclasses import replace
from types import SimpleNamespace as NS
from typing import Any

import pyray as rl
import pytest

from openpilot.starpilot.navigation import sidecar_config as config
from openpilot.starpilot.navigation.sidecar_status import Verdict, X10Status
from openpilot.starpilot.ui import sidecar_large
from openpilot.starpilot.ui.sidecar_flow import SidecarView, describe, host_feedback, port_feedback, token_feedback
from openpilot.starpilot.ui.sidecar_large import (
  BUTTON_GAP, BUTTON_HEIGHT, BUTTON_WIDTH, BUTTONS_LEFT, LEFT, SidecarKeyboard, SidecarLarge, SidecarLargeView,
)
from openpilot.system.ui.widgets import DialogResult

HOST = '192.168.1.20'
READY = X10Status(Verdict.READY, HOST, 8765, 'app 1.4.2, up 2 h', api=1, version='1.4.2', engine='ready', uptime_s=7200, http_status=200)
CONFIGURED = SidecarView(document=config.DOC_OK, enabled=True, host=HOST, port=9000, token_length=24, status=READY, status_age_s=3.0)
SCREEN = rl.Rectangle(0, 0, 2160, 1080)


class FakeButton:
  def __init__(self, text='', action=None, *args, **kwargs):
    self.text, self.action, self.args, self.kwargs = text, action, args, kwargs
    self.enabled, self.visible, self.rect = True, True, None

  def set_text(self, text):
    self.text = text

  def set_enabled(self, enabled):
    self.enabled = enabled

  def set_visible(self, visible):
    self.visible = visible

  def render(self, rect):
    if self.is_visible:
      self.rect = rect

  @property
  def is_enabled(self):
    return self.enabled() if callable(self.enabled) else self.enabled

  @property
  def is_visible(self):
    return self.visible() if callable(self.visible) else self.visible

  def show_event(self):
    pass

  def hide_event(self):
    pass


class FakeLabel:
  def __init__(self, text='', *args, **kwargs):
    self.text, self.rect = text, None

  def set_text(self, text):
    self.text = text

  def render(self, rect):
    self.rect = rect


class FakeKeyboard:
  """Stands in for SidecarKeyboard and remembers how the page built it."""
  built: list['FakeKeyboard'] = []

  def __init__(self, title, rule, feedback, **kwargs):
    self.title, self.rule, self.feedback, self.kwargs = title, rule, feedback, kwargs
    self.text, self.cleared = '', 0
    self.callback: Any = None
    FakeKeyboard.built.append(self)

  def set_text(self, text):
    self.text = text

  def set_callback(self, callback):
    self.callback = callback

  def clear(self):
    self.cleared += 1


class FlowStub:
  def __init__(self, view=CONFIGURED):
    self.view, self.notice, self.token, self.calls = view, '', 'abcd' * 10, []

  def activate(self):
    self.calls.append('activate')

  def deactivate(self):
    self.calls.append('deactivate')

  def update(self):
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
  pushed, dialogs = [], []
  FakeKeyboard.built = []
  monkeypatch.setattr(sidecar_large, 'Button', FakeButton)
  monkeypatch.setattr(sidecar_large, 'UnifiedLabel', FakeLabel)
  monkeypatch.setattr(sidecar_large, 'SidecarKeyboard', FakeKeyboard)
  monkeypatch.setattr(sidecar_large, 'alert_dialog', lambda text: dialogs.append(('alert', text)) or ('alert', text))
  monkeypatch.setattr(sidecar_large, 'ConfirmDialog', lambda *args, **kwargs: dialogs.append(('confirm', args, kwargs)) or ('confirm', args, kwargs))
  monkeypatch.setattr(sidecar_large, 'gui_app', NS(push_widget=pushed.append, pop_widget=lambda: None))
  monkeypatch.setattr(rl, 'draw_rectangle_rec', lambda *_args: None)
  flow = FlowStub()
  page = SidecarLargeView(flow)
  buttons = {'switch': page._switch, 'address': page._address, 'port': page._port, 'token': page._token, 'reveal': page._reveal,
             'test': page._test, 'clear': page._clear, 'close': page._close}
  return NS(page=page, flow=flow, buttons=buttons, pushed=pushed, dialogs=dialogs)


def frame(made, view=CONFIGURED):
  made.flow.view = view
  made.page._update_state()
  made.page._render(SCREEN)


def test_the_buttons_and_what_each_does(made):
  assert [button.text for button in made.buttons.values()] == ['Turn on', 'Change', 'Change', 'Change', 'Show', 'Test connection', 'Clear', 'Close']
  frame(made)
  assert made.buttons['switch'].text == 'Turn off'
  made.buttons['switch'].action()
  made.buttons['test'].action()
  assert made.flow.calls == [('enabled', False), 'test_now']
  frame(made, SidecarView(document=config.DOC_OK))
  assert made.buttons['switch'].text == 'Turn on'
  made.buttons['switch'].action()
  assert made.flow.calls[-1] == ('enabled', True)


def test_the_page_says_what_describe_says(made):
  frame(made)
  words = describe(CONFIGURED)
  texts = {label.text for label in made.page._values}
  assert texts == {words.toggle_value, words.address, words.port, words.token}
  assert made.page._status.text == 'X10 ready' and made.page._detail.text == 'app 1.4.2, up 2 h, checked 3 s ago'
  assert made.page._warning.text == ''
  frame(made, replace(CONFIGURED, editable=False))
  assert made.page._warning.text == 'Park the car to change the X10 settings.'
  frame(made, SidecarView(document=config.DOC_OK, host=HOST, token_length=5, token_problem='The token needs at least 10 characters (now 5)'))
  assert made.page._warning.text == 'The token needs at least 10 characters (now 5).' and made.page._values[3].text == 'needs fixing'


def test_buttons_that_change_settings_are_locked_while_driving_or_saving(made):
  for view, editable in ((CONFIGURED, True), (replace(CONFIGURED, editable=False), False), (replace(CONFIGURED, busy=True), False)):
    frame(made, view)
    for name in ('switch', 'address', 'port', 'token', 'clear'):
      assert made.buttons[name].is_enabled is editable, (name, view)
    assert made.buttons['test'].is_enabled and made.buttons['close'].is_enabled
    assert made.buttons['reveal'].is_enabled is view.editable  # the token is shown only while parked


def test_clear_is_only_offered_when_there_is_something_to_clear(made):
  for view, offered in ((CONFIGURED, True), (SidecarView(document=config.DOC_MISSING), False),
                        (SidecarView(document=config.DOC_OK, host=HOST, port=9000), False),
                        (SidecarView(document=config.DOC_OK, token_length=12, token_problem=''), True),
                        (SidecarView(document=config.DOC_CORRUPT), True), (SidecarView(document=config.DOC_UNREADABLE), False)):
    frame(made, view)
    assert made.buttons['clear'].is_enabled is offered, view
  frame(made, SidecarView(document=config.DOC_MISSING))
  made.buttons['clear'].action()
  assert made.dialogs == []


def test_show_token_appears_only_when_a_token_is_saved(made):
  frame(made)
  assert made.buttons['reveal'].is_visible
  frame(made, SidecarView(document=config.DOC_MISSING))
  assert not made.buttons['reveal'].is_visible


def test_a_notice_is_shown_once_as_a_message(made):
  made.flow.notice = 'Set the X10 address before turning the sidecar on'
  frame(made)
  frame(made)
  assert made.dialogs == [('alert', 'Set the X10 address before turning the sidecar on')] and len(made.pushed) == 1


def test_the_address_keyboard_takes_the_saved_address_and_hands_back_what_was_typed(made):
  frame(made)
  made.buttons['address'].action()
  [keyboard] = FakeKeyboard.built
  assert keyboard.kwargs == {'minimum': 7, 'maximum': 15, 'secret': False, 'numeric': True}
  assert keyboard.title == 'X10 address' and '192.168.1.20' in keyboard.rule and keyboard.text == HOST and keyboard.feedback is host_feedback
  keyboard.text = '192.168.1.99'
  keyboard.callback(DialogResult.CONFIRM)
  assert made.flow.calls[-1] == ('host', '192.168.1.99') and keyboard.cleared == 1
  keyboard.callback(DialogResult.CANCEL)
  assert made.flow.calls == [('host', '192.168.1.99')] and keyboard.cleared == 2  # a cancel changes nothing
  assert made.pushed == [keyboard]


def test_the_port_keyboard(made):
  frame(made)
  made.buttons['port'].action()
  [keyboard] = FakeKeyboard.built
  assert keyboard.kwargs == {'minimum': 1, 'maximum': 5, 'secret': False, 'numeric': True} and keyboard.text == '9000'
  assert keyboard.feedback is port_feedback
  keyboard.text = '9100'
  keyboard.callback(DialogResult.CONFIRM)
  assert made.flow.calls[-1] == ('port', '9100')


def test_the_token_keyboard_is_masked_with_the_eye_and_starts_empty(made):
  frame(made)
  made.buttons['token'].action()
  [keyboard] = FakeKeyboard.built
  assert keyboard.kwargs == {'minimum': 10, 'maximum': 128, 'secret': True, 'numeric': False} and keyboard.feedback is token_feedback
  assert keyboard.text == '' and '10 to 128 characters' in keyboard.rule  # the saved token is never prefilled
  keyboard.text = '0123456789abcdef'
  keyboard.callback(DialogResult.CONFIRM)
  assert made.flow.calls[-1] == ('token', '0123456789abcdef') and keyboard.cleared == 1


def test_show_token_lists_groups_of_four_on_lines_of_sixteen_or_says_there_is_none(made):
  made.flow.token = 'abcdefghijklmnopqrstuvwxyz0123456789'
  made.buttons['reveal'].action()
  assert made.dialogs[-1] == ('alert', 'Token\nabcd efgh ijkl mnop\nqrst uvwx yz01 2345\n6789')
  made.flow.token = ''
  made.buttons['reveal'].action()
  assert made.dialogs[-1] == ('alert', 'No token is saved.')


def test_show_token_does_nothing_while_driving(made):
  frame(made, replace(CONFIGURED, editable=False))
  made.buttons['reveal'].action()
  assert made.dialogs == []


def test_clear_asks_first_and_only_a_confirmation_clears(made):
  frame(made)
  made.buttons['clear'].action()
  kind, args, kwargs = made.dialogs[-1]
  assert kind == 'confirm' and 'remove the saved token' in args[0] and args[1] == 'Clear'
  assert made.flow.calls == []
  kwargs['callback'](DialogResult.CANCEL)
  assert made.flow.calls == []
  kwargs['callback'](DialogResult.CONFIRM)
  assert made.flow.calls == ['clear']
  made.dialogs.clear()
  frame(made, replace(CONFIGURED, editable=False))
  made.buttons['clear'].action()
  assert made.dialogs == []


def test_the_flow_runs_only_while_the_page_is_shown(made):
  made.page.show_event()
  made.page.hide_event()
  assert made.flow.calls == ['activate', 'deactivate']


def test_the_entry_pushes_a_page_for_its_flow(monkeypatch):
  pushed = []
  monkeypatch.setattr(sidecar_large, 'gui_app', NS(push_widget=pushed.append))
  monkeypatch.setattr(sidecar_large, 'SidecarLargeView', lambda flow: ('page', flow))
  flow = FlowStub()
  SidecarLarge(flow).open()
  assert pushed == [('page', flow)]


# ---------------------------------------------------------------- layout on the 2160x1080 screen

def boxes(made):
  """Every drawn button and label of the last frame as (name, left, top, right, bottom)."""
  result = []
  for name, button in made.buttons.items():
    if button.rect is not None and button.is_visible:
      result.append((name, button.rect.x, button.rect.y, button.rect.x + button.rect.width, button.rect.y + button.rect.height))
  labels = [('title', made.page._title), ('status', made.page._status), ('detail', made.page._detail), ('warning', made.page._warning),
            *((f'label{index}', label) for index, label in enumerate(made.page._labels)),
            *((f'value{index}', label) for index, label in enumerate(made.page._values))]
  for name, label in labels:
    if label.rect is not None:
      result.append((name, label.rect.x, label.rect.y, label.rect.x + label.rect.width, label.rect.y + label.rect.height))
  return result


@pytest.mark.parametrize('view', [CONFIGURED, SidecarView(document=config.DOC_MISSING), replace(CONFIGURED, editable=False),
                                  SidecarView(document=config.DOC_OK, host=HOST, token_length=5, token_problem='x', enabled=True)])
def test_nothing_overlaps_and_everything_is_on_screen(made, view):
  frame(made, view)
  items = boxes(made)
  for name, left, top, right, bottom in items:
    assert 0 <= left < right <= 2160 and 0 <= top < bottom <= 1080, name
  buttons = [item for item in items if item[0] in made.buttons]
  for index, first in enumerate(buttons):
    for second in buttons[index + 1:]:
      assert first[3] <= second[1] or second[3] <= first[1] or first[4] <= second[2] or second[4] <= first[2], (first[0], second[0])
  rows = {name: item for item in items for name in [item[0]]}
  for index in range(4):  # a value never runs under the button beside it
    value = rows[f'value{index}']
    beside = [button for button in buttons if button[2] < value[4] and value[2] < button[4] and button[1] >= value[1]]
    assert all(value[3] <= button[1] for button in beside), (index, beside)


def test_the_main_action_of_every_row_sits_in_one_column_with_its_label(made):
  frame(made)
  rows = {name: (left, top, right, bottom) for name, left, top, right, bottom in boxes(made)}
  for index, name in enumerate(('switch', 'address', 'port', 'token')):
    button = rows[name]
    assert (button[0], button[2] - button[0], button[3] - button[1]) == (BUTTONS_LEFT, BUTTON_WIDTH, BUTTON_HEIGHT)
    assert rows[f'label{index}'][1] == button[1] and rows[f'label{index}'][0] == LEFT  # label and button share the row
  assert rows['reveal'][2] + BUTTON_GAP == rows['token'][0] and rows['reveal'][1] == rows['token'][1]
  assert rows['close'][2] <= 2160 and rows['close'][3] <= 1080 and rows['close'][1] > rows['clear'][1]


# ---------------------------------------------------------------- the large keyboard with a live line

def keyboard(monkeypatch, feedback=token_feedback, *, minimum=10, maximum=128, secret=True, numeric=False, text=''):
  """A SidecarKeyboard without the graphics resources the base class loads; its drawing is recorded instead."""
  titles, colors, drawn = [], [], []

  def fake_init(self, **kwargs):
    self.kwargs = kwargs
    self._layout_name = 'lowercase'
    self._min_text_size = kwargs['min_text_size']
    self._input_box = NS(text=text)
    self._sub_title = NS(set_text_color=colors.append)

  monkeypatch.setattr(sidecar_large.Keyboard, '__init__', fake_init)
  monkeypatch.setattr(sidecar_large.Keyboard, 'set_title', lambda self, title, sub_title='': titles.append((title, sub_title)))
  monkeypatch.setattr(sidecar_large.Keyboard, '_render', lambda self, rect: drawn.append(self._min_text_size) or 'drawn')
  widget = SidecarKeyboard('X10 token', 'rule of the field', feedback, minimum=minimum, maximum=maximum, secret=secret, numeric=numeric)
  return NS(widget=widget, titles=titles, colors=colors, drawn=drawn)


def test_the_large_keyboard_is_built_with_the_limits_the_masking_and_the_eye(monkeypatch):
  made = keyboard(monkeypatch)
  assert made.widget.kwargs == {'max_text_size': 128, 'min_text_size': 10, 'password_mode': True, 'show_password_toggle': True}
  assert made.titles == [('X10 token', 'rule of the field')] and made.widget._layout_name == 'lowercase'
  plain = keyboard(monkeypatch, host_feedback, minimum=7, maximum=15, secret=False, numeric=True)
  assert plain.widget.kwargs == {'max_text_size': 15, 'min_text_size': 7, 'password_mode': False, 'show_password_toggle': False}
  assert plain.widget._layout_name == 'numbers'  # the digit keys first for the address and the port


@pytest.mark.parametrize('text, shown, error', [('', 'rule of the field', False), ('abc', 'need 10 characters (3)', True),
                                                ('has space 12345', 'ASCII only, no spaces', True), ('x' * 24, '24 characters', False)])
def test_the_line_under_the_title_is_the_rule_until_something_is_typed(monkeypatch, text, shown, error):
  made = keyboard(monkeypatch, text=text)
  result = made.widget.line()
  assert (result.text, result.error) == (shown, error)
  assert made.widget._render(SCREEN) == 'drawn'
  assert made.titles[-1] == ('X10 token', shown) and made.colors[-1] == (sidecar_large.ORANGE if error else sidecar_large.GREY)


def test_the_title_is_rewritten_only_when_the_line_changes(monkeypatch):
  made = keyboard(monkeypatch, text='abc')
  for _ in range(5):
    made.widget._render(SCREEN)
  assert made.titles == [('X10 token', 'rule of the field'), ('X10 token', 'need 10 characters (3)')]
  made.widget._input_box.text = 'abcd'
  made.widget._render(SCREEN)
  assert made.titles[-1] == ('X10 token', 'need 10 characters (4)') and len(made.titles) == 3


@pytest.mark.parametrize('feedback, text, valid', [(token_feedback, 'x' * 10, True), (token_feedback, 'x' * 9, False),
                                                   (token_feedback, 'has space 12345', False), (token_feedback, 'x' * 129, False),
                                                   (host_feedback, '192.168.1.20', True), (host_feedback, '8.8.8.8', False),
                                                   (host_feedback, '192.168.1', False), (port_feedback, '8765', True),
                                                   (port_feedback, '70000', False), (port_feedback, '', False)])
def test_the_check_key_is_on_only_for_a_text_the_writer_accepts(monkeypatch, feedback, text, valid):
  made = keyboard(monkeypatch, feedback, minimum=3, text=text)
  made.widget._render(SCREEN)
  # The keyboard enables its check key when the text has at least _min_text_size characters: an unreachable minimum keeps it off.
  assert made.drawn == [3 if valid else SidecarKeyboard.NEVER]
  made.widget._input_box.text = ''
  made.widget._render(SCREEN)
  assert made.drawn[-1] == SidecarKeyboard.NEVER  # nothing typed, nothing to confirm
