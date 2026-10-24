"""Saved wheel and road side choices, and a bounded cabin-camera proposal.

The cabin camera estimates which side has the wheel. It does not classify the
side of the road. Road side follows wheel side only as a default; an explicit
TrafficSide choice always wins.
"""

from __future__ import annotations

from dataclasses import dataclass
import math

SIDES = ("left", "right")
MIN_PROPOSAL_SAMPLES = 20
MIN_PROPOSAL_SPAN_S = 1.25
MAX_PROPOSAL_GAP_S = 0.35
PROPOSAL_STALE_S = 0.75
FACE_PROBABILITY = 0.75
LEFT_PROBABILITY = 0.15
RIGHT_PROBABILITY = 0.85


def _saved_side(params, key: str) -> str | None:
  value = params.get(key)
  if isinstance(value, bytes):
    try:
      value = value.decode("ascii")
    except UnicodeDecodeError:
      return None
  return value if value in SIDES else None


def _detected_wheel_side(params) -> str | None:
  value = params.get("IsRhdDetected")
  if value is True or value in ("1", b"1"):
    return "right"
  if value is False or value in ("0", b"0"):
    return "left"
  return None


def default_traffic_side(wheel_side: str | None) -> str | None:
  return {"right": "left", "left": "right"}.get(wheel_side)


@dataclass(frozen=True)
class DrivingSideSnapshot:
  wheel_side: str | None
  wheel_saved: bool
  traffic_side: str | None
  traffic_saved: bool


class DrivingSideSettings:
  def __init__(self, params, parked):
    self.params = params
    self.parked = parked

  def snapshot(self) -> DrivingSideSnapshot:
    wheel_saved = _saved_side(self.params, "DriverSide")
    wheel = wheel_saved or _detected_wheel_side(self.params)
    traffic_saved = _saved_side(self.params, "TrafficSide")
    return DrivingSideSnapshot(wheel, wheel_saved is not None,
                               traffic_saved or default_traffic_side(wheel), traffic_saved is not None)

  def save_wheel_side(self, side: str) -> bool:
    if side not in SIDES:
      raise ValueError("Invalid wheel side")
    if not self.parked():
      return False
    self.params.put("DriverSide", side, block=True)
    return True

  def clear_wheel_side(self) -> bool:
    if not self.parked():
      return False
    self.params.remove("DriverSide")
    return True

  def save_traffic_side(self, side: str) -> bool:
    if side not in SIDES:
      raise ValueError("Invalid traffic side")
    if not self.parked():
      return False
    self.params.put("TrafficSide", side, block=True)
    return True

  def clear_traffic_side(self) -> bool:
    if not self.parked():
      return False
    self.params.remove("TrafficSide")
    return True


class WheelSideProposal:
  """Require consecutive fresh, confident cabin-model frames before proposing.

  A changed side, obscured face, ambiguous wheel probability, stale frame, or
  stream gap discards the partial proposal. This never writes a Param.
  """

  def __init__(self):
    self.side: str | None = None
    self.candidate: str | None = None
    self.count = 0
    self.started_at: float | None = None
    self.last_at: float | None = None

  def reset(self) -> None:
    self.side = self.candidate = None
    self.count = 0
    self.started_at = self.last_at = None

  def expire(self, now: float) -> str | None:
    if self.last_at is not None and now - self.last_at > PROPOSAL_STALE_S:
      self.reset()
    return self.candidate

  def observe(self, wheel_on_right: float, left_face: float, right_face: float, at: float) -> str | None:
    values = (wheel_on_right, left_face, right_face, at)
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in values):
      self.reset()
      return None
    if not 0 <= wheel_on_right <= 1 or not 0 <= left_face <= 1 or not 0 <= right_face <= 1:
      self.reset()
      return None
    if self.last_at is not None and (at <= self.last_at or at - self.last_at > MAX_PROPOSAL_GAP_S):
      self.reset()
    side = ("right" if wheel_on_right >= RIGHT_PROBABILITY and right_face >= FACE_PROBABILITY else
            "left" if wheel_on_right <= LEFT_PROBABILITY and left_face >= FACE_PROBABILITY else None)
    if side is None:
      self.reset()
      return None
    if side != self.side:
      self.side = side
      self.count = 0
      self.started_at = at
      self.candidate = None
    self.count += 1
    self.last_at = at
    if self.count >= MIN_PROPOSAL_SAMPLES and self.started_at is not None and at - self.started_at >= MIN_PROPOSAL_SPAN_S:
      self.candidate = side
    return self.candidate
