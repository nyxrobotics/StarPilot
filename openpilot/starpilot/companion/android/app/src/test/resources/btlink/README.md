# Shared test vectors of the Sidecar Bluetooth link

Read by the Companion's JUnit tests (`ContractVectorsTest`, `AllowlistTest`, `VehiclePushTest`, `MapContractVectorsTest`, `SidecarMapJobsTest`). They are test data only: documentation coordinates, a placeholder token, a test key.

| File | Content | Made by |
| --- | --- | --- |
| `allowlist.json` | 273 requests with the expected verdict of the allow-list (four shapes; version 2: the first 119 are version 1, the 154 `nav-*` cases carry the beacon's nav object) | the Sidecar project: `tools/fixtures/btlab/contract/allowlist.json` (copy) |
| `results.json` | 34 job results (status, encoding, payload, size, SHA-256) with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/results.json` (copy) |
| `chunk-targets.json` | 25 chunk paths with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/chunk-targets.json` (copy) |
| `age.json` | 15 aging cases: a body, the time the frame waited, the exact body that goes on (the position's `age_ms`, and `age_ms` and `mage` of the nav object, saturating at 60000 and 5000) | the Sidecar project: `tools/fixtures/btlab/contract/age.json` (copy) |
| `frames.json` | frames made by this repository's `galaxy_bluetooth/bridge/protocol.py`, and what its `open_message` returns | the Sidecar project: `tools/fixtures/btlab/frames.json` (copy) |
| `push-frames.json` | push frames, their notification packets and the `age_ms` rule, from the reference modules of the Sidecar project and from `protocol.py` | `bin/gen_push_vectors.py` of the Sidecar job (see its note) |
| `allowlist-diff.json` | 960 generated requests (560 without nav, 400 with a nav object) with the verdict of the reference allow-list | `bin/gen_allowlist_diff.py` of the Sidecar job |

| `allowlist-v3.json` | contract version 3: 779 requests, the 273 of `allowlist.json` first and unchanged, then 506 for the five map shapes (`roadtiles`, `stile`, `geocode`, `suggest`, `retrieve`) | the Sidecar project: `tools/fixtures/btlab/contract/allowlist-v3.json` (copy) |
| `results-v3.json` | 121 job results of the five map kinds (limits, statuses, encodings, digest, size) with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/results-v3.json` (copy) |
| `bundles.json`, `bundles-differential.json` | 94 hand-written and 1,500 generated `RTB1` bundles (the Companion carries them as bytes; the comma reads them) | the Sidecar project: `tools/fixtures/btlab/contract/` (copies) |
| `lane-map.json` | the planner (16 cases), the lane scripts (6), the classes and deadlines of the kinds, the announcement bodies (24), the health flags (26), the meter (10) | the Sidecar project: `tools/fixtures/btlab/contract/lane-map.json` (copy) |
| `allowlist-diff-v3.json` | 2,506 generated requests for the five map shapes with the verdict of the reference allow-list (version 3 and version 2) | `tools/btcompanion/gen_allowlist_diff_v3.py` of the Sidecar project |

The first files (those marked copy) are copies: a change of a rule is a change of the Sidecar project's vectors, reviewed there, and the copy is replaced. `ContractVectorsTest` counts the cases so that a truncated copy fails.
The raw-body frames in `../ble/request-raw.json` are made by the app (`BleWire.sealRaw`) and opened by `tests/test_ble_wire_vectors.py` with the bridge's `open_message`.
