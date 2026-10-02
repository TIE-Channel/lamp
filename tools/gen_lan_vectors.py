"""Generates app/src/test/resources/tuya_lan_vectors.json: messages of Tuya's local Wi-Fi protocol
3.4 built by the reference implementation (tinytuya), which WifiLink.kt must reproduce.

    pip install tinytuya
    python tools/gen_lan_vectors.py

Nonces and the clock are fixed so that the messages come out the same every time.
"""

import hashlib
import hmac
import importlib
import json
from pathlib import Path

import tinytuya
from tinytuya.core import command_types as CT
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message, unpack_message

LOCAL_KEY = b"0123456789abcdef"
DEVICE_ID = "bf0123456789abcdefghij"
LOCAL_NONCE = bytes(range(16))
REMOTE_NONCE = bytes(range(100, 116))
TIME = 1790000000

# The module, not the class of the same name that tinytuya.core exports.
xenon = importlib.import_module("tinytuya.core.XenonDevice")
xenon.os.urandom = lambda n: LOCAL_NONCE[:n]
xenon.time.time = lambda: TIME


def from_device(seq, cmd, key, plain):
    """A message as the lamp sends it: a return code, then the encrypted payload."""
    body = b"\x00\x00\x00\x00" + (AESCipher(key).encrypt(plain, False) if plain else b"")
    return pack_message(TuyaMessage(seq, cmd, 0, body, 0, True, 0x55AA, False), hmac_key=key)


d = tinytuya.Device(DEVICE_ID, "127.0.0.1", LOCAL_KEY.decode(), version=3.4)
out = {"localKey": LOCAL_KEY.decode(), "localNonce": LOCAL_NONCE.hex(), "remoteNonce": REMOTE_NONCE.hex(), "time": TIME}

out["start"] = d._encode_message(d._negotiate_session_key_generate_step_1()).hex()
answer = from_device(0x8AB8, CT.SESS_KEY_NEG_RESP, LOCAL_KEY, REMOTE_NONCE + hmac.new(LOCAL_KEY, LOCAL_NONCE, hashlib.sha256).digest())
out["startAnswer"] = answer.hex()
unpacked = unpack_message(answer, hmac_key=LOCAL_KEY)
out["finish"] = d._encode_message(d._negotiate_session_key_generate_step_3(unpacked)).hex()
d._negotiate_session_key_generate_finalize()
session = d.local_key
out["sessionKey"] = session.hex()

out["query"] = d._encode_message(d.generate_payload(CT.DP_QUERY)).hex()
out["queryAnswer"] = from_device(0x8AB9, CT.DP_QUERY_NEW, session, b'{"dps":{"20":true,"21":"white","22":1000,"23":0,"26":0}}').hex()
out["control"] = d._encode_message(d.generate_payload(CT.CONTROL, {"20": False, "22": 10})).hex()
out["controlAnswer"] = from_device(0xF16D, CT.CONTROL_NEW, session, b"").hex()

target = Path(__file__).resolve().parent.parent / "app/src/test/resources/tuya_lan_vectors.json"
target.write_text(json.dumps(out, indent=1), encoding="utf-8")
print(f"written {target}")
