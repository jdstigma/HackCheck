# HackCheck Router Backend

A small Postgres + FastAPI backend that ingests network flow data from
ntopng (running on a box connected to your switch's mirrored port) and
serves it up for the HackCheck Android app's Router screen.

## Architecture

```
TP-Link switch (port mirroring)
        |
        v
   ntopng  (runs on a Pi/PC plugged into the mirrored port,
            captures + summarizes traffic, exposes a REST API)
        |
        v
   poller.py  (polls ntopng every POLL_INTERVAL_SECONDS,
               writes flows into Postgres)
        |
        v
   Postgres  (devices + network_flows tables)
        |
        v
   main.py (FastAPI)  (serves /devices, /flows, /top-talkers, /topology, etc.)
        |
        +----------------------------------+
        v                                  v
   HackCheck Android app           topology.html
   (Router screen, over LAN)       (network graph, in a browser)
```

Postgres and the API can also be queried directly from Power BI for
dashboarding, independent of the Android app.

## Setup

1. **Postgres**: create a database and user (locally, in a container,
   or on the same box as the rest of this).

2. **Python environment**:
   ```
   cd router-backend
   python -m venv .venv
   source .venv/bin/activate   # or .venv\Scripts\activate on Windows
   pip install -r requirements.txt
   ```

3. **Config**: copy `.env.example` to `.env` and fill in your real
   `DATABASE_URL`, ntopng host, and credentials. `.env` is gitignored --
   never commit it.

4. **Create tables**:
   ```
   python init_db.py
   ```

5. **Run the API**:
   ```
   uvicorn main:app --reload --host 0.0.0.0 --port 8000
   ```
   Open `http://localhost:8000/docs` to test every endpoint interactively
   before the Android app ever calls it.

6. **Run the poller** (separate terminal, keeps running):
   ```
   python poller.py
   ```

## Endpoints

- `GET /health` -- liveness check
- `GET /devices` -- all known devices, most recently active first
- `GET /flows?since_minutes=60&device_id=&limit=500` -- raw flow records
- `GET /devices/{id}/traffic-summary?since_minutes=60` -- totals for one device
- `GET /top-talkers?since_minutes=60&limit=10` -- devices ranked by bandwidth
- `GET /topology?since_minutes=60&limit_edges=200` -- aggregated graph data
  (nodes + edges) for the visualization below

## Dashboard and network topology visualization

Two browser-based views live under `/static`, once `main.py` is running:

- **`http://localhost:8000/static/dashboard.html`** -- top talkers, known
  devices, and recent flows as readable tables. This is the main view for
  day-to-day checking; start here.
- **`http://localhost:8000/static/topology.html`** -- a force-directed
  graph of every endpoint your network has talked to, green nodes are
  known devices (matched by MAC), blue nodes are external endpoints, edge
  thickness scales with traffic volume. Multiple flows between the same
  src/dst pair aggregate into one edge rather than drawing separately, so
  the graph stays readable.

Both pages link to each other, and both have the backend URL and time
window editable directly on the page (same pattern as the Android app's
Router screen) since they'll differ per deployment.

Built with vanilla JS + vis-network (topology graph only, loaded from a
CDN, no build step). The data-handling logic in both pages (`formatBytes`,
`escapeHtml`, the render functions, and the `/topology` endpoint's
aggregation logic) was tested by extracting the JS and actually executing
it against real API-shaped data -- including edge cases like null
hostnames and HTML-escaping untrusted device-reported strings -- but the
actual visual rendering/layout in a real browser hasn't been confirmed
yet for either page.

## Known gaps / things to verify once you have a live ntopng instance

- **ntopng REST API shape**: `poller.py` targets the commonly documented
  v2 REST API, but exact field names and endpoint paths vary by ntopng
  version. The first real run will likely need small adjustments --
  see the docstring at the top of `poller.py`.
- **No auth on the FastAPI endpoints yet**: fine for a home LAN behind
  your router's firewall, but add an API key or similar before exposing
  this beyond your local network.
- **Schema migrations**: `init_db.py` uses `create_all`, which only
  creates missing tables -- it won't alter existing ones if you change
  a model later. Fine for now; look at Alembic if this grows.
