"""Small, validated navigation geometry for the paired phone's BLE map overlay."""

import heapq
import math


MAX_ROUTE_INPUT_POINTS = 20_000
MAX_ROUTE_OUTPUT_POINTS = 512
MIN_DETAIL_DEGREES_SQUARED = (2.0 / 111_320.0) ** 2  # About two metres.


def _coordinate(value):
  if type(value) is not dict:
    return None
  latitude, longitude = value.get('latitude'), value.get('longitude')
  if (type(latitude) not in (int, float) or type(longitude) not in (int, float) or
      not math.isfinite(latitude) or not math.isfinite(longitude) or
      not -90 <= latitude <= 90 or not -180 <= longitude <= 180):
    return None
  return [round(longitude, 6), round(latitude, 6)]


def destination_coordinates(value):
  """GeoJSON order [longitude, latitude], or None for an invalid/unset destination."""
  return _coordinate(value)


def bounded_route(points):
  """Preserve the largest bends of a route while bounding BLE and notification payloads.

  The navigation engine already bounds its route at 20,000 vertices. Reject malformed
  geometry in full rather than drawing a misleading line through skipped vertices.
  """
  if type(points) is not list or len(points) > MAX_ROUTE_INPUT_POINTS:
    return []
  coordinates = [_coordinate(point) for point in points]
  if any(point is None for point in coordinates):
    return []
  if len(coordinates) <= MAX_ROUTE_OUTPUT_POINTS:
    return coordinates

  # Longitude needs the cosine correction so a bend's importance is comparable in
  # the east/west and north/south directions. The output keeps the original values.
  cosine = max(1e-6, math.cos(math.radians(sum(point[1] for point in (coordinates[0], coordinates[-1])) / 2)))
  projected = [(point[0] * cosine, point[1]) for point in coordinates]
  selected = {0, len(coordinates) - 1}
  heap = []

  def add_segment(first, last):
    if last - first < 2:
      return
    ax, ay = projected[first]
    bx, by = projected[last]
    dx, dy = bx - ax, by - ay
    length_squared = dx * dx + dy * dy
    best_index, best_error = first + 1, -1.0
    for index in range(first + 1, last):
      px, py = projected[index]
      error = ((px - ax) * dy - (py - ay) * dx) ** 2 / length_squared if length_squared else (px - ax) ** 2 + (py - ay) ** 2
      if error > best_error:
        best_index, best_error = index, error
    heapq.heappush(heap, (-best_error, first, best_index, last))

  add_segment(0, len(coordinates) - 1)
  while heap and len(selected) < MAX_ROUTE_OUTPUT_POINTS:
    negative_error, first, index, last = heapq.heappop(heap)
    if -negative_error <= MIN_DETAIL_DEGREES_SQUARED:
      break
    selected.add(index)
    add_segment(first, index)
    add_segment(index, last)
  return [coordinates[index] for index in sorted(selected)]
