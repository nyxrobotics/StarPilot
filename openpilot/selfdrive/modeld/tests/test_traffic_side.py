"""The driving model receives road side independently of cabin camera side."""

import pytest

from openpilot.selfdrive.modeld.traffic_side import saved_traffic_side, traffic_convention_index


class FakeParams:
  def __init__(self, values: dict[str, object]):
    self.values = values

  def get(self, key: str) -> object:
    return self.values[key]


@pytest.mark.parametrize(('saved', 'is_rhd', 'index'), [
  ('left', False, 1),
  ('left', True, 1),
  ('right', False, 0),
  ('right', True, 0),
  (None, False, 0),
  (None, True, 1),
])
def test_model_traffic_convention_keeps_manual_road_side_independent_of_driver_side(saved, is_rhd, index):
  assert traffic_convention_index(saved, is_rhd) == index


@pytest.mark.parametrize(('stored_road', 'stored_wheel', 'expected'), [
  ('left', 'right', 'left'), ('right', 'left', 'right'),
  ('left', 'left', 'left'), ('right', 'right', 'right'),
  (None, 'right', 'left'), (None, 'left', 'right'),
  ('other', 'right', 'left'), ('', 'left', 'right'),
  (None, None, None), ('LEFT', 'other', None), (True, None, None),
])
def test_explicit_traffic_side_wins_then_confirmed_driver_side_gives_default(stored_road, stored_wheel, expected):
  values = {'TrafficSide': stored_road, 'DriverSide': stored_wheel}
  params = FakeParams(values)
  assert saved_traffic_side(params) == expected
