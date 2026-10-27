# Shared test vectors of the Sidecar Bluetooth link

Read by the Companion's JUnit tests (`ContractVectorsTest`, `AllowlistTest`, `VehiclePushTest`). They are test data only: documentation coordinates, a placeholder token, a test key.

| File | Content | Made by |
| --- | --- | --- |
| `allowlist.json` | 273 requests with the expected verdict of the allow-list (four shapes; version 2: the first 119 are version 1, the 154 `nav-*` cases carry the beacon's nav object) | the Sidecar project: `tools/fixtures/btlab/contract/allowlist.json` (copy) |
| `results.json` | 34 job results (status, encoding, payload, size, SHA-256) with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/results.json` (copy) |
| `chunk-targets.json` | 25 chunk paths with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/chunk-targets.json` (copy) |
| `age.json` | 15 aging cases: a body, the time the frame waited, the exact body that goes on (the position's `age_ms`, and `age_ms` and `mage` of the nav object, saturating at 60000 and 5000) | the Sidecar project: `tools/fixtures/btlab/contract/age.json` (copy) |
| `frames.json` | frames made by this repository's `galaxy_bluetooth/bridge/protocol.py`, and what its `open_message` returns | the Sidecar project: `tools/fixtures/btlab/frames.json` (copy) |
| `push-frames.json` | push frames, their notification packets and the `age_ms` rule, from the reference modules of the Sidecar project and from `protocol.py` | `bin/gen_push_vectors.py` of the Sidecar job (see its note) |
| `allowlist-diff.json` | 960 generated requests (560 without nav, 400 with a nav object) with the verdict of the reference allow-list | `bin/gen_allowlist_diff.py` of the Sidecar job |

The first five files are copies: a change of a rule is a change of the Sidecar project's vectors, reviewed there, and the copy is replaced. `ContractVectorsTest` counts the cases so that a truncated copy fails.
The raw-body frames in `../ble/request-raw.json` are made by the app (`BleWire.sealRaw`) and opened by `tests/test_ble_wire_vectors.py` with the bridge's `open_message`.
