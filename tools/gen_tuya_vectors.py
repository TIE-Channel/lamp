"""Generates app/src/test/resources/tuya_vectors.json: packets built by the reference
implementation (PlusPlus-ua/ha_tuya_ble), which the Kotlin port in Tuya.kt must reproduce.

    python tools/gen_tuya_vectors.py path/to/ha_tuya_ble/custom_components/tuya_ble/tuya_ble
"""

import hashlib
import importlib.util
import json
import sys
import types
from pathlib import Path

# The reference imports its Bluetooth stack at module level; the packet code does not use it.
for name, attrs in {
    "bleak": [],
    "bleak.backends": [],
    "bleak.backends.device": ["BLEDevice"],
    "bleak.backends.scanner": ["AdvertisementData"],
    "bleak.exc": ["BleakDBusError"],
    "bleak_retry_connector": ["BLEAK_BACKOFF_TIME", "BLEAK_RETRY_EXCEPTIONS", "BleakClientWithServiceCache", "BleakError", "BleakNotFoundError", "establish_connection"],
}.items():
    module = types.ModuleType(name)
    for attr in attrs:
        setattr(module, attr, () if attr == "BLEAK_RETRY_EXCEPTIONS" else type(attr, (Exception,), {}))
    sys.modules[name] = module

path = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("tuya_ble", path / "__init__.py", submodule_search_locations=[str(path)])
pkg = importlib.util.module_from_spec(spec)
sys.modules["tuya_ble"] = pkg
spec.loader.exec_module(pkg)
ref = sys.modules["tuya_ble.tuya_ble"]
Code = sys.modules["tuya_ble.const"].TuyaBLECode

LOCAL_KEY = "0123456789abcdef"
UUID = "uuid0123456789ab"
DEVICE_ID = "bf0123456789abcdefghij"
SRAND = bytes.fromhex("a1b2c3d4e5f6")

dev = object.__new__(ref.TuyaBLEDevice)
dev._local_key = LOCAL_KEY[:6].encode()
dev._login_key = hashlib.md5(dev._local_key).digest()
dev._session_key = hashlib.md5(dev._local_key + SRAND).digest()
dev._protocol_version = 3
dev._device_info = types.SimpleNamespace(uuid=UUID, local_key=LOCAL_KEY, device_id=DEVICE_ID)

cases = []
for i, (code, data, response_to) in enumerate([
    (Code.FUN_SENDER_DEVICE_INFO, b"", 0),
    (Code.FUN_SENDER_PAIR, bytes(dev._build_pairing_request()), 0),
    (Code.FUN_SENDER_DPS, bytes.fromhex("140101011602040000" "03e8"), 0),
    (Code.FUN_SENDER_DEVICE_STATUS, b"", 0),
    (Code.FUN_RECEIVE_DP, b"", 7),
    (Code.FUN_RECEIVE_DP, bytes(range(70)), 0),  # long enough for four GATT chunks
]):
    iv = bytes((i * 17 + j) & 0xFF for j in range(16))
    ref.secrets.token_bytes = lambda n, iv=iv: iv
    seq = i + 1
    packets = dev._build_packets(seq, code, data, response_to)
    cases.append({"seq": seq, "code": code.value, "data": data.hex(), "responseTo": response_to, "iv": iv.hex(), "chunks": [bytes(p).hex() for p in packets]})

out = {
    "localKey": LOCAL_KEY, "uuid": UUID, "deviceId": DEVICE_ID, "srand": SRAND.hex(),
    "pairing": bytes(dev._build_pairing_request()).hex(),
    "crc": {"data": bytes(range(40)).hex(), "value": ref.TuyaBLEDevice._calc_crc16(bytes(range(40)))},
    "cases": cases,
}
target = Path(__file__).resolve().parent.parent / "app/src/test/resources/tuya_vectors.json"
target.write_text(json.dumps(out, indent=1), encoding="utf-8")
print(len(cases), "cases ->", target)
