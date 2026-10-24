import json
import math

from openpilot.starpilot.galaxy.navigation_map import bounded_route, destination_coordinates


def test_destination_uses_geojson_order_and_rejects_invalid_coordinates():
  assert destination_coordinates({'latitude': 35.12345678, 'longitude': 139.98765432}) == [139.987654, 35.123457]
  for invalid in (None, {'latitude': True, 'longitude': 139}, {'latitude': math.nan, 'longitude': 139},
                  {'latitude': 91, 'longitude': 139}, {'latitude': 35, 'longitude': -181}):
    assert destination_coordinates(invalid) is None


def test_bounded_route_keeps_ends_and_largest_bends():
  # A long route with a pronounced detour should not be reduced by uniform sampling.
  points = [{'latitude': 35.0 + index * 0.00001 + (0.01 if index == 3001 else 0),
             'longitude': 139.0 + index * 0.00001} for index in range(8000)]
  route = bounded_route(points)
  assert 3 <= len(route) <= 512
  assert route[0] == [139.0, 35.0]
  assert route[-1] == [139.07999, 35.07999]
  assert [139.03001, 35.04001] in route


def test_bounded_route_rejects_malformed_geometry_without_connecting_across_it():
  points = [{'latitude': 35.0, 'longitude': 139.0}, {'latitude': 35.1, 'longitude': 139.1}]
  assert bounded_route(points) == [[139.0, 35.0], [139.1, 35.1]]
  assert bounded_route(points + [{'latitude': 35.2, 'longitude': math.inf}]) == []
  assert bounded_route([points[0]] * 20_001) == []


def test_zigzag_at_engine_limit_still_fits_the_phone_reply():
  points = [{'latitude': 35.0 + (0.001 if index % 2 else 0), 'longitude': 139.0 + index * 0.000001}
            for index in range(20_000)]
  route = bounded_route(points)
  assert len(route) == 512
  assert route[0] == [139.0, 35.0] and route[-1] == [139.019999, 35.001]
  assert len(json.dumps({'route': route}).encode()) < 32_768
