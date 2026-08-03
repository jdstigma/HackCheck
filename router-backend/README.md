# HackCheck Router Backend

A small Postgres + FastAPI backend that ingests data from ntopng
(traffic flows), optionally Pi-hole (DNS queries), and optionally
Suricata (signature-based intrusion alerts) -- all typically running on
one dedicated box connected to your switch's mirrored port -- and serves
it up for the HackCheck Android app's Router screen and the browser
dashboard.

## Architecture

```
TP-Link switch (port mirroring)
        |
        v
   ntopng          Pi-hole (opt.)      Suricata (opt.)
   (flow data)      (DNS queries)      (signature alerts, eve.json)
        |                |                     |
        v                v                     v
   poller.py     pihole_poller.py     suricata_poller.py
        |                |                     |
        +----------------+---------------------+
                          v
                     Postgres
       (devices, network_flows, dns_queries, security_alerts)
                          |
                          v
          main.py (FastAPI) -- /devices, /flows, /top-talkers,
          /topology, /dns-queries, /dns-top-domains, /alerts, etc.
                          |
        +-----------------------------------+
        v                 v                  v
   HackCheck app    dashboard.html    topology.html
   (Router screen)  (tables, browser)  (graph, browser)
```

Pi-hole and Suricata are both optional -- ntopng alone is enough for
the Router screen and topology graph to work. Adding one or both gives
domain-level visibility (Pi-hole) and signature-based alerting
(Suricata) alongside the flow data ntopng already provides.

Postgres and the API can also be queried directly from Power BI for
dashboarding, independent of the Android app.

## Setup

**Automated option (Pi/PC that will run ntopng + this backend together):**
```
curl -fsSL https://raw.githubusercontent.com/jdstigma/HackCheck/main/router-backend/setup-pi.sh | bash
```
Installs ntopng, asks which network interface is on your switch's
mirrored port, clones this repo, sets up the Python environment, writes
the sudoers entry needed for the app's Start/Stop box controls, and
optionally walks through `.env`/Postgres details interactively --
including installing Postgres itself and creating the role/database if
you point it at `localhost` and confirm, not just writing a connection
string for something that doesn't exist yet. Also offers Pi-hole,
Suricata, kiosk mode, Hydra, and Wireshark, each independently optional.
Nothing about your specific machine or username is hardcoded -- it
prompts for what it needs (or falls back to sane defaults if run
somewhere without a real terminal attached, e.g. `HACKCHECK_INTERFACE=eth0
HACKCHECK_SETUP_ENV=no bash setup-pi.sh` for a fully non-interactive
run). Worth reading before piping anything into bash, including this.

**Manual steps** (or if you're setting up Postgres/the API on a
different machine than ntopng):

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
- `GET /ntopng/status`, `POST /ntopng/start`, `POST /ntopng/stop` --
  control the local ntopng systemd service. Only works when this backend
  runs on the same box as ntopng, and requires the sudoers entry
  documented at the top of `ntopng_control.py`. Without that sudoers
  setup, these return a clear error rather than failing silently --
  everything else in this backend works fine without it.
- `GET /dns-queries?since_minutes=60&blocked_only=false&limit=200` --
  raw DNS query records from Pi-hole
- `GET /dns-top-domains?since_minutes=60&limit=20` -- most-queried
  domains, with blocked-count alongside total count
- `GET /alerts?since_minutes=1440&limit=200` -- signature-based alerts
  from Suricata, most severe and most recent first

## Optional standalone tools (Hydra, Wireshark)

`setup-pi.sh` can also install Hydra (credential brute-force testing --
your own devices/authorized use only) and Wireshark (packet analysis,
configured for non-root capture). Neither integrates with the
poller/Postgres/dashboard pipeline above -- they're standalone tools for
manual home-lab use, installed for convenience since they're common
network-diagnostics companions to everything else on this box.

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
- **Pi-hole API shape**: `pihole_poller.py` and `pihole_client.py` were
  tested end-to-end against a mock Pi-hole server matching v6's
  documented REST/session-auth API (real auth flow, real session
  handling, real query parsing all verified), but never against an
  actual live Pi-hole instance. Auth/session logic should be solid;
  the query response field names are the part most likely to need a
  small adjustment against a real instance -- see the docstring at the
  top of `pihole_poller.py`.
- **Suricata eve.json shape**: `suricata_poller.py`'s file-tailing and
  parsing logic (including only-new-lines behavior and malformed-line
  handling) was tested against real simulated eve.json files, but the
  exact field availability depends on your Suricata config/version --
  see the docstring at the top of `suricata_poller.py`.
- **No auth on the FastAPI endpoints yet**: fine for a home LAN behind
  your router's firewall, but add an API key or similar before exposing
  this beyond your local network.
- **Schema migrations**: `init_db.py` uses `create_all`, which only
  creates missing tables -- it won't alter existing ones if you change
  a model later. Fine for now; look at Alembic if this grows.
