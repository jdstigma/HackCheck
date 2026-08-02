"""
suricata_poller.py -- tails Suricata's eve.json log for "alert" events
and writes them into Postgres.

Run with:
    python suricata_poller.py

eve.json is JSON-lines format (one JSON object per line), containing
many event types (flow, dns, http, tls, stats, alert, ...) -- this only
cares about event_type == "alert". Field names below (alert.signature,
alert.severity, alert.category, src_ip, dest_ip, proto) match Suricata's
long-stable eve.json alert shape, but config can affect which fields are
present -- if this comes up empty against a real Suricata instance with
real alerts firing, print(raw_line) to check the actual shape first.

Starts reading from the END of the file on first run (not the
beginning) -- eve.json can already contain a large amount of history by
the time this poller starts, and re-ingesting all of it on every
restart would duplicate rows. This means alerts that fired before the
poller's first run won't be backfilled -- acceptable for a live
monitoring tool, worth knowing if you're specifically hunting for
something that already happened.

Requires this process's user to have read access to eve.json --
typically owned by the suricata user/group. If you hit a
PermissionError, add your user to the appropriate group (check with
`ls -l /var/log/suricata/eve.json` on your system) rather than running
this poller as root.
"""

import json
import os
import time
from datetime import datetime, timezone

from dotenv import load_dotenv
from sqlalchemy.orm import Session

from database import SessionLocal
from models import SecurityAlert

load_dotenv()

EVE_JSON_PATH = os.getenv("SURICATA_EVE_JSON_PATH", "/var/log/suricata/eve.json")
POLL_INTERVAL_SECONDS = int(os.getenv("SURICATA_POLL_INTERVAL_SECONDS", "10"))


def parse_alert_line(line: str) -> dict | None:
    """Returns a dict ready for SecurityAlert(**dict), or None if this
    line isn't a parseable alert event."""
    line = line.strip()
    if not line:
        return None

    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        # Can happen if we read a line Suricata hasn't finished writing
        # yet -- not a real error, just try again next poll.
        return None

    if event.get("event_type") != "alert":
        return None

    alert = event.get("alert", {})
    signature = alert.get("signature")
    if not signature:
        return None

    timestamp_str = event.get("timestamp")
    try:
        # Suricata's timestamp format: "2026-08-02T23:15:00.123456+0000"
        timestamp = datetime.strptime(timestamp_str, "%Y-%m-%dT%H:%M:%S.%f%z") if timestamp_str else None
    except (ValueError, TypeError):
        timestamp = None
    if timestamp is None:
        timestamp = datetime.now(timezone.utc)

    return {
        "timestamp": timestamp,
        "signature": signature[:500],  # matches the column's String(500) limit
        "severity": alert.get("severity"),
        "category": alert.get("category"),
        "src_ip": event.get("src_ip"),
        "dst_ip": event.get("dest_ip"),
        "protocol": event.get("proto"),
    }


def store_alerts(db: Session, parsed_alerts: list[dict]) -> int:
    for parsed in parsed_alerts:
        db.add(SecurityAlert(**parsed))
    db.commit()
    return len(parsed_alerts)


def tail_new_alerts(file_handle) -> list[dict]:
    """Reads whatever new lines are available right now (non-blocking --
    doesn't wait for more to arrive) and returns the parsed alert dicts."""
    parsed = []
    for line in file_handle:
        result = parse_alert_line(line)
        if result:
            parsed.append(result)
    return parsed


if __name__ == "__main__":
    if not os.path.exists(EVE_JSON_PATH):
        raise SystemExit(
            f"{EVE_JSON_PATH} doesn't exist. Is Suricata installed and running? "
            f"Set SURICATA_EVE_JSON_PATH in .env if it's somewhere else."
        )

    print(f"Tailing {EVE_JSON_PATH} every {POLL_INTERVAL_SECONDS}s. Ctrl+C to stop.")
    with open(EVE_JSON_PATH, "r") as f:
        f.seek(0, os.SEEK_END)  # start from EOF -- see module docstring

        while True:
            new_alerts = tail_new_alerts(f)
            if new_alerts:
                db = SessionLocal()
                try:
                    count = store_alerts(db, new_alerts)
                    print(f"[{datetime.now(timezone.utc).isoformat()}] stored {count} alerts")
                finally:
                    db.close()
            time.sleep(POLL_INTERVAL_SECONDS)
