from __future__ import annotations
"""
pihole_poller.py -- pulls recent DNS queries from Pi-hole and writes them
into Postgres on a loop.

Run with:
    python pihole_poller.py

Field names below (time, type, domain, client, status) match Pi-hole
v6's documented /api/queries response shape. If your instance returns
something different, this is the first thing to check -- add a
print(raw_queries[0]) the first time you run this against a real
Pi-hole and compare against what's assumed in parse_query() below.
"""

import os
import time
from datetime import datetime, timezone

from dotenv import load_dotenv
from sqlalchemy.orm import Session

from database import SessionLocal
from models import Device, DnsQuery
from pihole_client import PiholeClient

load_dotenv()

PIHOLE_URL = os.getenv("PIHOLE_URL")
PIHOLE_PASSWORD = os.getenv("PIHOLE_PASSWORD")
POLL_INTERVAL_SECONDS = int(os.getenv("PIHOLE_POLL_INTERVAL_SECONDS", "30"))

# Pi-hole's "status" field uses several distinct strings for blocked
# queries (gravity list, blacklist, regex, etc.) -- treat all of them as
# blocked=True. "OK"/"CACHE"/"FORWARDED" and similar are allowed queries.
BLOCKED_STATUSES = {
    "GRAVITY", "BLACKLIST", "REGEX", "DENYLIST", "GRAVITY_CNAME",
    "DENYLIST_CNAME", "REGEX_CNAME", "EXTERNAL_BLOCKED_IP",
    "EXTERNAL_BLOCKED_NULL", "EXTERNAL_BLOCKED_NXRA",
}


def get_or_create_device(db: Session, client_ip: str) -> Device | None:
    """Pi-hole reports client IP, not MAC -- unlike ntopng's flows, we
    don't get a MAC address here, so we can't reliably create a NEW
    device row (would risk duplicate/ghost devices keyed by IP, which
    can change via DHCP). Only attach to a device if one already exists
    matching this IP's last-known association -- otherwise leave
    device_id null. This is intentionally conservative."""
    # No IP-to-device mapping exists yet in this schema -- Device is keyed
    # by MAC, and DnsQuery only has the client IP. Leaving this as a
    # deliberate no-op / null-device path for now rather than guessing;
    # a real IP<->MAC join would need ARP table data or matching against
    # NetworkFlow.src_ip, which is a reasonable next step but not done here.
    return None


def parse_query(raw: dict) -> dict | None:
    domain = raw.get("domain")
    client = raw.get("client")
    if not domain or not client:
        return None

    epoch_time = raw.get("time")
    timestamp = (
        datetime.fromtimestamp(epoch_time, tz=timezone.utc)
        if epoch_time is not None
        else datetime.now(timezone.utc)
    )

    status = raw.get("status", "")
    return {
        "timestamp": timestamp,
        "domain": domain,
        "client_ip": client,
        "query_type": raw.get("type"),
        "blocked": status in BLOCKED_STATUSES,
    }


def store_queries(db: Session, raw_queries: list[dict]) -> int:
    stored = 0
    for raw in raw_queries:
        parsed = parse_query(raw)
        if parsed is None:
            continue
        device = get_or_create_device(db, parsed["client_ip"])
        db.add(
            DnsQuery(
                device_id=device.id if device else None,
                timestamp=parsed["timestamp"],
                domain=parsed["domain"],
                client_ip=parsed["client_ip"],
                query_type=parsed["query_type"],
                blocked=parsed["blocked"],
            )
        )
        stored += 1
    db.commit()
    return stored


def poll_once(client: PiholeClient):
    raw_queries = client.recent_queries()
    if not raw_queries:
        return

    db = SessionLocal()
    try:
        count = store_queries(db, raw_queries)
        print(f"[{datetime.now(timezone.utc).isoformat()}] stored {count} DNS queries")
    finally:
        db.close()


if __name__ == "__main__":
    if not PIHOLE_URL:
        raise SystemExit("PIHOLE_URL not set in .env")

    client = PiholeClient(PIHOLE_URL, PIHOLE_PASSWORD)
    print(f"Polling {PIHOLE_URL} every {POLL_INTERVAL_SECONDS}s. Ctrl+C to stop.")
    while True:
        poll_once(client)
        time.sleep(POLL_INTERVAL_SECONDS)
