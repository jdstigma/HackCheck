from __future__ import annotations
"""
poller.py -- pulls active flows from ntopng's REST API and writes them
into Postgres on a loop.

Run with:
    python poller.py

Auth note (confirmed against a real instance, ntopng 6.7.260707): the
REST API does NOT accept HTTP Basic Auth -- unauthenticated/invalid
requests get a 302 redirect to /lua/login.lua regardless of an
Authorization header. It needs a real session cookie, obtained the same
way the browser does: POST to /authorize.html with the login form's
actual field names (found by inspecting the rendered login page's HTML --
the visible username input is named `_username` for display only; JS
copies it into the hidden `user` field before submit, which is what the
server actually reads), then reuse that session's cookie for the REST
calls. No CSRF token is required by this form. If ntopng's REST API
paths change in a future version, adjust NTOPNG_FLOWS_ENDPOINT and the
response-parsing in fetch_flows() to match.
"""

import os
import time
from datetime import datetime, timezone

import requests
from dotenv import load_dotenv
from sqlalchemy.orm import Session

from database import SessionLocal
from models import Device, NetworkFlow

load_dotenv()

NTOPNG_URL = os.getenv("NTOPNG_URL")
NTOPNG_USERNAME = os.getenv("NTOPNG_USERNAME")
NTOPNG_PASSWORD = os.getenv("NTOPNG_PASSWORD")
POLL_INTERVAL_SECONDS = int(os.getenv("POLL_INTERVAL_SECONDS", "60"))

# See the module docstring -- confirm this matches your ntopng version.
NTOPNG_FLOWS_ENDPOINT = "/lua/rest/v2/get/flow/active.lua"

_session: requests.Session | None = None


def _login() -> requests.Session:
    """Logs into ntopng via its actual login form and returns a Session
    carrying the resulting session cookie.

    Connection: close is set on the session because ntopng's built-in
    HTTP server doesn't reliably support keep-alive/connection reuse --
    confirmed live: reusing the pooled connection from this login POST
    for the next request got the connection forcibly closed mid-request
    (RemoteDisconnected), even called back-to-back. Forcing a fresh TCP
    connection per request avoids relying on that.
    """
    session = requests.Session()
    session.headers.update({"Connection": "close"})
    session.post(
        f"{NTOPNG_URL}/authorize.html",
        data={"user": NTOPNG_USERNAME, "password": NTOPNG_PASSWORD, "referer": ""},
        timeout=10,
    )
    return session


def _get_session() -> requests.Session:
    """Reuses one logged-in session across polls rather than logging in
    every cycle (wasteful, and repeated logins could trip rate limiting)."""
    global _session
    if _session is None:
        _session = _login()
    return _session


def fetch_flows() -> list[dict]:
    """Calls ntopng's REST API and returns a list of raw flow dicts.
    Returns an empty list (rather than raising) on any failure, so one
    bad poll doesn't crash the whole loop -- errors are printed instead."""
    if not NTOPNG_URL:
        print("NTOPNG_URL not set in .env -- skipping this poll")
        return []

    url = f"{NTOPNG_URL}{NTOPNG_FLOWS_ENDPOINT}"
    global _session
    try:
        session = _get_session()
        response = session.get(url, params={"ifid": 0}, timeout=10)
        if "/lua/login.lua" in response.url:
            # Session expired or the first login didn't take -- log in
            # again once and retry, rather than silently returning empty
            # forever (which is exactly what happened before this fix).
            _session = _login()
            response = _session.get(url, params={"ifid": 0}, timeout=10)
        response.raise_for_status()
        payload = response.json()
        # ntopng v2 REST responses typically wrap the real data in "rsp" --
        # confirm this against your actual response shape (print(payload)
        # once, the first time you run this against a real instance).
        return payload.get("rsp", {}).get("data", [])
    except requests.RequestException as e:
        print(f"ntopng request failed: {e}")
        return []
    except ValueError as e:
        print(f"ntopng response wasn't valid JSON: {e}")
        return []


def get_or_create_device(db: Session, mac_address: str, hostname: str | None) -> Device:
    """Looks up a device by MAC; creates it if this is the first time
    we've seen it. Updates last_seen either way."""
    device = db.query(Device).filter(Device.mac_address == mac_address).first()
    now = datetime.now(timezone.utc)

    if device is None:
        device = Device(mac_address=mac_address, hostname=hostname, first_seen=now, last_seen=now)
        db.add(device)
        db.flush()  # assigns device.id without a full commit yet
    else:
        device.last_seen = now
        if hostname and not device.hostname:
            device.hostname = hostname

    return device


def store_flows(db: Session, raw_flows: list[dict]) -> int:
    """Converts ntopng's raw flow dicts into NetworkFlow rows and saves
    them. Returns the count actually stored (some rows may be skipped if
    they're missing required fields).

    NOTE: the field names below (cli.mac, cli.ip, srv.ip, etc.) match
    ntopng's typical v2 flow object shape. Verify against a real payload --
    print(raw_flows[0]) the first time you run this live and adjust the
    .get() calls if the keys differ.
    """
    stored = 0
    for flow in raw_flows:
        src_ip = flow.get("cli.ip") or flow.get("cli", {}).get("ip")
        dst_ip = flow.get("srv.ip") or flow.get("srv", {}).get("ip")
        if not src_ip or not dst_ip:
            continue  # skip malformed rows rather than crashing the batch

        client_mac = flow.get("cli.mac") or flow.get("cli", {}).get("mac")
        device = get_or_create_device(db, client_mac, hostname=None) if client_mac else None

        db.add(
            NetworkFlow(
                device_id=device.id if device else None,
                timestamp=datetime.now(timezone.utc),
                src_ip=src_ip,
                dst_ip=dst_ip,
                dst_port=flow.get("srv.port") or flow.get("srv", {}).get("port"),
                protocol=flow.get("proto") or flow.get("l4_proto"),
                bytes_sent=flow.get("bytes.sent", 0) or 0,
                bytes_recv=flow.get("bytes.rcvd", 0) or 0,
            )
        )
        stored += 1

    db.commit()
    return stored


def poll_once():
    raw_flows = fetch_flows()
    if not raw_flows:
        return

    db = SessionLocal()
    try:
        count = store_flows(db, raw_flows)
        print(f"[{datetime.now(timezone.utc).isoformat()}] stored {count} flows")
    finally:
        db.close()


if __name__ == "__main__":
    print(f"Polling {NTOPNG_URL} every {POLL_INTERVAL_SECONDS}s. Ctrl+C to stop.")
    while True:
        poll_once()
        time.sleep(POLL_INTERVAL_SECONDS)
