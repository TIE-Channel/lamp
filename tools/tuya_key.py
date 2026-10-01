"""Fetches the lamp's Tuya credentials and writes lamp-config.json for the phone app.

The lamp only obeys a phone that knows its "local key". The key is created when the lamp is
added in the Smart Life app and can only be read from the Tuya cloud, with the owner's consent:

    pip install tuya-device-sharing-sdk segno
    python tools/tuya_key.py <user code> [--mac <the lamp's Bluetooth address>]

1. The user code is shown in Smart Life: Me -> gear icon -> Account and Security -> User Code.
2. The script saves a QR code to tools/tuya-login.png; scan it with the Smart Life app
   (the scan button on its home screen) and confirm.
3. lamp-config.json appears in the project root. It holds the key: keep it out of git.

Then copy it into the app (debug build, phone on USB):

    adb shell "run-as home.lampremote sh -c 'cat > files/config.json'" < lamp-config.json
"""

import argparse
import json
import sys
import time
from pathlib import Path

import segno
from tuya_sharing import LoginControl, Manager

# The public client id of Tuya's Home Assistant integration, which this login flow belongs to.
CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
SCHEMA = "haauthorize"
ROOT = Path(__file__).resolve().parent.parent

# Data point codes of a Tuya light, newest naming first.
CODES = {
    "switch": ["switch_led"],
    "bright": ["bright_value_v2", "bright_value"],
    "temp": ["temp_value_v2", "temp_value"],
    "countdown": ["countdown_1", "countdown"],
}


def login(user_code: str) -> dict:
    control = LoginControl()
    response = control.qr_code(CLIENT_ID, SCHEMA, user_code)
    if not response.get("success"):
        sys.exit(f"Tuya refused the user code: {response.get('msg', response)}")
    token = response["result"]["qrcode"]
    picture = ROOT / "tools" / "tuya-login.png"
    segno.make(f"tuyaSmart--qrLogin?token={token}").save(str(picture), scale=10, border=4)
    print(f"QR code saved to {picture} - scan it with the Smart Life app", flush=True)
    for _ in range(90):
        time.sleep(2)
        ok, info = control.login_result(token, CLIENT_ID, user_code)
        if ok:
            picture.unlink(missing_ok=True)
            return info
    sys.exit("Timed out waiting for the QR code to be scanned")


def dp_ids(manager: Manager, device_id: str) -> dict[str, int]:
    """Maps data point codes (switch_led, ...) to the numbers used over Bluetooth."""
    response = manager.customer_api.get(f"/v1.0/m/life/devices/{device_id}/status")
    relations = (response.get("result") or {}).get("dpStatusRelationDTOS") or []
    return {r["statusCode"]: int(r["dpId"]) for r in relations}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("user_code")
    parser.add_argument("--mac", help="Bluetooth address of the lamp, if known")
    parser.add_argument("--name", help="device name in Smart Life, when there are several devices")
    args = parser.parse_args()

    info = login(args.user_code)
    manager = Manager(CLIENT_ID, args.user_code, info["terminal_id"], info["endpoint"], info)
    manager.update_device_cache()
    devices = list(manager.device_map.values())
    if args.name:
        devices = [d for d in devices if d.name == args.name]
    lights = [d for d in devices if d.category in ("dj", "dd", "xdd", "fwd", "dc")] or devices
    if len(lights) != 1:
        names = ", ".join(f"{d.name!r} ({d.category})" for d in devices) or "none"
        sys.exit(f"Expected one lamp in the account, found: {names}. Pass --name to choose.")
    lamp = lights[0]

    ids = dp_ids(manager, lamp.id)
    print(f"Lamp: {lamp.name!r}, category {lamp.category}, product {lamp.product_id}")
    print("Data points:")
    for code, number in sorted(ids.items(), key=lambda item: item[1]):
        spec = lamp.status_range.get(code) or lamp.function.get(code)
        print(f"  {number:3} {code:20} {getattr(spec, 'type', '?'):8} {getattr(spec, 'values', '')}  now: {lamp.status.get(code)}")

    dps: dict[str, int | None] = {}
    for key, codes in CODES.items():
        code = next((c for c in codes if c in ids), None)
        dps[key] = ids[code] if code else None
        if code and key in ("bright", "temp"):
            spec = lamp.status_range.get(code) or lamp.function.get(code)
            limits = json.loads(getattr(spec, "values", "") or "{}")
            if key == "bright":
                dps["brightMin"] = limits.get("min", 10)
                dps["brightMax"] = limits.get("max", 1000)
            else:
                dps["tempMax"] = limits.get("max", 1000)
    if dps["switch"] is None or dps["bright"] is None:
        print("No switch/brightness data point found by name: the app will use its defaults (20, 22)")
        dps = {k: v for k, v in dps.items() if k not in ("switch", "bright")}

    config = {
        "deviceId": lamp.id,
        "uuid": lamp.uuid,
        "localKey": lamp.local_key,
        "productId": lamp.product_id,
        "mac": args.mac or "",
        "dps": dps,
    }
    target = ROOT / "lamp-config.json"
    target.write_text(json.dumps(config, indent=1), encoding="utf-8")
    print(f"Key received ({len(lamp.local_key)} characters). Written {target}")


if __name__ == "__main__":
    main()
