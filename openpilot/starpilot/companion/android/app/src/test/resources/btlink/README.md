# Shared test vectors of the Sidecar Bluetooth link

Read by the Companion's JUnit tests (`ContractVectorsTest`, `AllowlistTest`, `VehiclePushTest`). They are test data only: documentation coordinates, a placeholder token, a test key.

| File | Content | Made by |
| --- | --- | --- |
| `allowlist.json` | 119 requests with the expected verdict of the allow-list (four shapes) | the Sidecar project: `tools/fixtures/btlab/contract/allowlist.json` (copy) |
| `results.json` | 34 job results (status, encoding, payload, size, SHA-256) with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/results.json` (copy) |
| `chunk-targets.json` | 25 chunk paths with accept or refuse | the Sidecar project: `tools/fixtures/btlab/contract/chunk-targets.json` (copy) |
| `frames.json` | frames made by this repository's `galaxy_bluetooth/bridge/protocol.py`, and what its `open_message` returns | the Sidecar project: `tools/fixtures/btlab/frames.json` (copy) |
| `push-frames.json` | push frames, their notification packets and the `age_ms` rule, from the reference modules of the Sidecar project and from `protocol.py` | `bin/gen_push_vectors.py` of the Sidecar job (see its note) |
| `allowlist-diff.json` | 560 generated requests with the verdict of the reference allow-list | `bin/gen_allowlist_diff.py` of the Sidecar job |

The first four files are copies: a change of a rule is a change of the Sidecar project's vectors, reviewed there, and the copy is replaced. `ContractVectorsTest` counts the cases so that a truncated copy fails.
The raw-body frames in `../ble/request-raw.json` are made by the app (`BleWire.sealRaw`) and opened by `tests/test_ble_wire_vectors.py` with the bridge's `open_message`.
