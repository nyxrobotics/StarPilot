"""The driving model's road traffic convention, independent of steering-wheel position.

The model's two-element input is [right-side traffic, left-side traffic]. A
confirmed TrafficSide preference takes precedence. A confirmed DriverSide gives
the default (LHD -> right, RHD -> left) before the first monitoring frame;
otherwise live driver monitoring preserves the historical behavior. Saved
preferences are read when modeld starts, so an offroad settings change takes
effect on the next drive.
"""
from __future__ import annotations

from typing import Protocol


class _ParamsReader(Protocol):
  def get(self, key: str) -> object: ...


def saved_traffic_side(params: _ParamsReader) -> str | None:
  road = params.get('TrafficSide')
  if road in ('left', 'right'):
    return road
  wheel = params.get('DriverSide')
  return 'left' if wheel == 'right' else 'right' if wheel == 'left' else None


def traffic_convention_index(saved_side: str | None, is_rhd: bool) -> int:
  """Index of the active entry in [right-side traffic, left-side traffic]."""
  if saved_side == 'left':
    return 1
  if saved_side == 'right':
    return 0
  return int(is_rhd)
