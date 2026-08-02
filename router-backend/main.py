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
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import Integer, func
from sqlalchemy.orm import Session

from database import get_db
from models import Device, DnsQuery, NetworkFlow, SecurityAlert
from ntopng_control import ntopng_status, start_ntopng, stop_ntopng

app = FastAPI(title="HackCheck Router Backend")

# Permissive CORS so the topology visualization page (static/topology.html)
# can call this API from a browser -- whether served by this same app at
# /static/topology.html, or opened directly as a local file (file:// origin).
# Fine for a home-LAN tool behind your router's firewall; tighten this
# (specific origins, not "*") before exposing the API beyond your LAN.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET"],
    allow_headers=["*"],
)

app.mount("/static", StaticFiles(directory="static"), name="static")


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


@app.get("/topology")
def topology(
    since_minutes: int = Query(60, description="Look back this many minutes"),
    limit_edges: int = Query(200, le=2000, description="Cap on distinct src->dst pairs returned"),
    db: Session = Depends(get_db),
):
    """Network graph data for visualization: unique endpoints (nodes) and
    aggregated traffic between them (edges), for the topology.html page.

    An "edge" here is one src_ip -> dst_ip pair, with per-pair flow count
    and total bytes summed across every matching flow in the window --
    not one edge per raw flow row, which would make the graph unreadable
    for anything but a nearly idle network.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)

    edge_rows = (
        db.query(
            NetworkFlow.src_ip,
            NetworkFlow.dst_ip,
            NetworkFlow.device_id,
            func.count(NetworkFlow.id).label("flow_count"),
            func.coalesce(func.sum(NetworkFlow.bytes_sent + NetworkFlow.bytes_recv), 0).label("total_bytes"),
        )
        .filter(NetworkFlow.timestamp >= cutoff)
        .group_by(NetworkFlow.src_ip, NetworkFlow.dst_ip, NetworkFlow.device_id)
        .order_by(func.sum(NetworkFlow.bytes_sent + NetworkFlow.bytes_recv).desc())
        .limit(limit_edges)
        .all()
    )

    device_ids = {r.device_id for r in edge_rows if r.device_id is not None}
    devices_by_id = {d.id: d for d in db.query(Device).filter(Device.id.in_(device_ids)).all()}

    # Build the node set from every IP that appears as either a source or
    # destination -- a node is just an IP address; devices give some nodes
    # a friendlier label (hostname) where we have one.
    node_ids: dict[str, dict] = {}

    def ensure_node(ip: str, device_id: Optional[int]):
        if ip in node_ids:
            return
        device = devices_by_id.get(device_id) if device_id is not None else None
        node_ids[ip] = {
            "id": ip,
            "label": device.hostname if device and device.hostname else ip,
            "is_known_device": device is not None,
        }

    edges = []
    for r in edge_rows:
        ensure_node(r.src_ip, r.device_id)
        ensure_node(r.dst_ip, None)  # destination is rarely a known local device
        edges.append(
            {
                "source": r.src_ip,
                "target": r.dst_ip,
                "flow_count": r.flow_count,
                "total_bytes": r.total_bytes,
            }
        )

    return {
        "since_minutes": since_minutes,
        "nodes": list(node_ids.values()),
        "edges": edges,
    }


@app.get("/ntopng/status")
def get_ntopng_status():
    """Whether the local ntopng systemd service is running. Only
    meaningful when this backend runs on the same box as ntopng --
    see ntopng_control.py for the sudoers setup this depends on."""
    return ntopng_status()


@app.post("/ntopng/start")
def post_ntopng_start():
    """Starts the local ntopng systemd service. Requires the sudoers
    entry documented in ntopng_control.py -- without it, this returns
    ok: false with a clear error rather than silently failing."""
    return start_ntopng()


@app.post("/ntopng/stop")
def post_ntopng_stop():
    """Stops the local ntopng systemd service."""
    return stop_ntopng()


@app.get("/dns-queries")
def list_dns_queries(
    since_minutes: int = Query(60, description="Return queries from the last N minutes"),
    blocked_only: bool = Query(False, description="Only return blocked queries"),
    limit: int = Query(200, le=5000),
    db: Session = Depends(get_db),
):
    """Raw DNS query records from Pi-hole, most recent first."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)
    query = db.query(DnsQuery).filter(DnsQuery.timestamp >= cutoff)
    if blocked_only:
        query = query.filter(DnsQuery.blocked.is_(True))

    rows = query.order_by(DnsQuery.timestamp.desc()).limit(limit).all()
    return [
        {
            "id": q.id,
            "device_id": q.device_id,
            "timestamp": q.timestamp,
            "domain": q.domain,
            "client_ip": q.client_ip,
            "query_type": q.query_type,
            "blocked": q.blocked,
        }
        for q in rows
    ]


@app.get("/dns-top-domains")
def dns_top_domains(
    since_minutes: int = Query(60, description="Look back this many minutes"),
    limit: int = Query(20, le=200),
    db: Session = Depends(get_db),
):
    """Most-queried domains in the window, with blocked-count alongside
    total count -- the 'what is this network actually talking to, DNS-wise'
    view."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)

    rows = (
        db.query(
            DnsQuery.domain,
            func.count(DnsQuery.id).label("query_count"),
            func.sum(func.cast(DnsQuery.blocked, Integer)).label("blocked_count"),
        )
        .filter(DnsQuery.timestamp >= cutoff)
        .group_by(DnsQuery.domain)
        .order_by(func.count(DnsQuery.id).desc())
        .limit(limit)
        .all()
    )

    return [
        {
            "domain": r.domain,
            "query_count": r.query_count,
            "blocked_count": r.blocked_count or 0,
        }
        for r in rows
    ]


@app.get("/alerts")
def list_alerts(
    since_minutes: int = Query(1440, description="Return alerts from the last N minutes (default 24h)"),
    limit: int = Query(200, le=5000),
    db: Session = Depends(get_db),
):
    """Signature-based security alerts from Suricata, most recent and
    most severe first. Suricata severity: 1 = high, 3 = low."""
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=since_minutes)

    rows = (
        db.query(SecurityAlert)
        .filter(SecurityAlert.timestamp >= cutoff)
        .order_by(SecurityAlert.severity.asc(), SecurityAlert.timestamp.desc())
        .limit(limit)
        .all()
    )
    return [
        {
            "id": a.id,
            "device_id": a.device_id,
            "timestamp": a.timestamp,
            "signature": a.signature,
            "severity": a.severity,
            "category": a.category,
            "src_ip": a.src_ip,
            "dst_ip": a.dst_ip,
            "protocol": a.protocol,
        }
        for a in rows
    ]
