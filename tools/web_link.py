"""Prints the one-time setup link for the web page (web/), for a phone or computer without the app.

The link carries the lamp's key after "#", which browsers never send to the server; the page
moves it into its own storage on first open. Treat the link like the key itself.

    python tools/web_link.py https://tie-channel.github.io/lamp/ [--qr]

--qr also saves the link as tools/web-link.png, to be scanned with the phone's camera.
"""

import argparse
import base64
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base_url", help="where web/ is hosted, e.g. https://tie-channel.github.io/lamp/")
    parser.add_argument("--qr", action="store_true", help="also save the link as a QR code")
    args = parser.parse_args()

    config = json.loads((ROOT / "lamp-config.json").read_text(encoding="utf-8"))
    dps = {key: value for key, value in config.get("dps", {}).items() if value is not None}
    payload = {"deviceId": config["deviceId"], "uuid": config["uuid"], "localKey": config["localKey"], "dps": dps}
    encoded = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")
    link = f"{args.base_url}#c={encoded}"
    print(link)
    if args.qr:
        import segno

        picture = ROOT / "tools" / "web-link.png"
        segno.make(link).save(str(picture), scale=8, border=4)
        print(f"QR code saved to {picture}")


if __name__ == "__main__":
    main()
