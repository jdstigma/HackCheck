"""
main.py -- FastAPI app serving router/network data.

Run locally with:
    uvicorn main:app --reload --host 0.0.0.0 --port 8000

Then open http://localhost:8000/docs for the auto-generated interactive
API explorer (Swagger UI) -- test every endpoint from the browser before
the Android app ever calls it.

--host 0.0.0.0 (not just 127.0.0.1) matters here: it makes the server
reachable from other devices on your LAN, like your phone, not just
from this machine.
"""

from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, FastAPI, Query
from sqlalchemy import func
from sqlalchemy.orm import Session

from database import get_db
from models import Device, NetworkFlow

app = FastAPI(title="HackCheck Router Backend")


@app.get("/health")
def health_check():
    """Simple liveness check -- confirms the API process is up.
    Doesn't touch the database, so a 200 here doesn't guarantee Postgres
    is reachable; use /devices or /flows for that."""
    return {"status": "ok"}


@app.get("/devices")
def list_devices(db: Session = Depends(get_db)):
    """All known devices, most recently active first."""
    devices = db.query(Device).order_by(Device.last_seen.desc()).all()
    return [
        {
            "id": d.id,
            "mac_address": d.mac_address,
            "hostname": d.hostname,
            "first_seen": d.first_seen,
            "last_seen": d.last_seen,
        }
        for d in devices
    ]


@app.get("/flows")
def list_flows(
    since_minutes: int = Query(60, description="Return flows from the last N minutes"),
    device_id: Optional[int] = Query(None, description="Filter to one device"),
    limit: int = Query(500, le=5000),
    db: Session = Depends(get_db),
):
    """Raw flow records, most recent first. This is the detail view --
    for a summary by device, see /devices/{id}/traffic-summary."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)
    query = db.query(NetworkFlow).filter(NetworkFlow.timestamp >= cutoff)
    if device_id is not None:
        query = query.filter(NetworkFlow.device_id == device_id)

    flows = query.order_by(NetworkFlow.timestamp.desc()).limit(limit).all()
    return [
        {
            "id": f.id,
            "device_id": f.device_id,
            "timestamp": f.timestamp,
            "src_ip": f.src_ip,
            "dst_ip": f.dst_ip,
            "dst_port": f.dst_port,
            "protocol": f.protocol,
            "bytes_sent": f.bytes_sent,
            "bytes_recv": f.bytes_recv,
        }
        for f in flows
    ]


@app.get("/devices/{device_id}/traffic-summary")
def device_traffic_summary(
    device_id: int,
    since_minutes: int = Query(60, description="Summarize flows from the last N minutes"),
    db: Session = Depends(get_db),
):
    """Total bytes sent/received for one device over a time window --
    this is the aggregation your Android app's device-detail view wants,
    rather than pulling every raw flow row and summing client-side."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)

    totals = (
        db.query(
            func.coalesce(func.sum(NetworkFlow.bytes_sent), 0).label("total_sent"),
            func.coalesce(func.sum(NetworkFlow.bytes_recv), 0).label("total_recv"),
            func.count(NetworkFlow.id).label("flow_count"),
        )
        .filter(NetworkFlow.device_id == device_id, NetworkFlow.timestamp >= cutoff)
        .one()
    )

    return {
        "device_id": device_id,
        "since_minutes": since_minutes,
        "total_bytes_sent": totals.total_sent,
        "total_bytes_recv": totals.total_recv,
        "flow_count": totals.flow_count,
    }


@app.get("/top-talkers")
def top_talkers(
    since_minutes: int = Query(60, description="Look back this many minutes"),
    limit: int = Query(10, le=100),
    db: Session = Depends(get_db),
):
    """Devices ranked by total bytes (sent + received) in the time window --
    the 'who's using the most bandwidth right now' view."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)

    rows = (
        db.query(
            NetworkFlow.device_id,
            func.coalesce(func.sum(NetworkFlow.bytes_sent + NetworkFlow.bytes_recv), 0).label("total_bytes"),
        )
        .filter(NetworkFlow.timestamp >= cutoff, NetworkFlow.device_id.isnot(None))
        .group_by(NetworkFlow.device_id)
        .order_by(func.sum(NetworkFlow.bytes_sent + NetworkFlow.bytes_recv).desc())
        .limit(limit)
        .all()
    )

    device_ids = [r.device_id for r in rows]
    devices_by_id = {d.id: d for d in db.query(Device).filter(Device.id.in_(device_ids)).all()}

    return [
        {
            "device_id": r.device_id,
            "hostname": devices_by_id.get(r.device_id).hostname if r.device_id in devices_by_id else None,
            "mac_address": devices_by_id.get(r.device_id).mac_address if r.device_id in devices_by_id else None,
            "total_bytes": r.total_bytes,
        }
        for r in rows
    ]
