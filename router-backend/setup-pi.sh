#!/usr/bin/env bash
#
# setup-pi.sh -- automates ntopng box setup for HackCheck's router-backend.
#
# Run this ON the Pi/PC that will run ntopng + router-backend, not from
# your phone or main computer. Usage:
#
#   curl -fsSL https://raw.githubusercontent.com/jdstigma/HackCheck/main/router-backend/setup-pi.sh | bash
#
# or download it first and read it before running (recommended for
# anything piped into bash from the internet, including this one):
#
#   curl -fsSL https://raw.githubusercontent.com/jdstigma/HackCheck/main/router-backend/setup-pi.sh -o setup-pi.sh
#   less setup-pi.sh
#   bash setup-pi.sh
#
# This script asks for the details it actually needs (which network
# interface is on the mirrored port, whether to set up Postgres config
# now) rather than assuming them -- nothing about your specific machine
# is hardcoded. It reads prompts from /dev/tty specifically because
# `curl | bash` feeds the script itself into bash's stdin, so a normal
# `read` would otherwise try to read from the piped script instead of
# your keyboard. If no TTY is available at all (e.g. run inside another
# script or CI), it falls back to sane auto-detected defaults instead of
# hanging -- see the environment variable overrides below.
#
# Non-interactive overrides (set before running, to skip prompts):
#   HACKCHECK_INTERFACE=eth0
#   HACKCHECK_SETUP_ENV=yes|no
#
# What this does:
#   1. Installs ntopng via Docker (ntop's officially documented path for
#      Raspberry Pi/ARM64 -- their native apt packages have been broken
#      or missing for ARM for years), wrapped in a systemd service so
#      the app's Start/Stop controls still work via plain systemctl
#   2. Asks which network interface is connected to the mirrored port,
#      and configures ntopng to listen on it
#   3. Clones the HackCheck repo (skips if already present)
#   4. Sets up router-backend's Python venv + dependencies
#   5. Writes a scoped sudoers drop-in so router-backend can start/stop/
#      check ntopng via the app -- validated with visudo -c before
#      installing, never edits /etc/sudoers directly
#   6. Optionally walks through creating router-backend/.env
#      interactively (Postgres connection details) -- and if Postgres is
#      meant to run on this same box, offers to install it and create
#      the role/database too, not just build a connection string for
#      something that doesn't exist yet. Then runs init_db.py
#      automatically -- failure here (e.g. DATABASE_URL still a
#      placeholder) is reported but doesn't stop the rest of setup;
#      the systemd services set up later self-heal once it's fixed
#   7. Optionally installs Pi-hole via its own official installer (runs
#      interactively -- its setup UI, not this script's, since Pi-hole's
#      unattended-install config format isn't something this script
#      assumes or tries to replicate)
#   8. Optionally installs Suricata, points it at the same mirrored
#      interface, and grants this user read access to its alert log
#   9. Optionally sets up kiosk mode: boots this Pi straight into a
#      fullscreen browser showing the HackCheck dashboard (uses labwc,
#      the Wayland compositor Raspberry Pi OS Bookworm+ ships by default)
#  10. Optionally installs Hydra -- a credential brute-forcer for testing
#      YOUR OWN devices' password strength (home-lab/authorized use only,
#      not integrated with anything else here -- it's a standalone CLI
#      tool, not part of the poller/dashboard pipeline)
#  11. Optionally installs Wireshark, configured for non-root packet
#      capture (adds you to the 'wireshark' group -- also standalone,
#      for manual packet analysis, not integrated with the pipeline)
#  12. Optionally sets up systemd services for the backend and all
#      installed pollers, so they run continuously and survive reboots/
#      SSH disconnects rather than needing a terminal kept open forever
#      -- recommended for a box that lives permanently on your network
#
# Still manual after this (needs decisions only you can make):
#   - Switch port mirroring (that's switch-side, not this box)
#   - Running init_db.py and starting uvicorn
#   - If Pi-hole/Suricata were installed: their own poller scripts
#     (pihole_poller.py, suricata_poller.py) still need to be started
#     separately, same as poller.py for ntopng
#   - Postgres install/setup is now automated ABOVE if you point it at
#     localhost and say yes when asked -- still manual if you're running
#     Postgres on a different machine (point DB_HOST there instead and
#     set it up yourself, same as always for a remote database)

set -euo pipefail

REPO_URL="https://github.com/jdstigma/HackCheck.git"
REPO_DIR="HackCheck"
SUDOERS_FILE="/etc/sudoers.d/hackcheck-ntopng"

log() { echo -e "\n==> $1"; }

HAVE_TTY=false
if [ -c /dev/tty ]; then
    HAVE_TTY=true
fi

# Reads a line from the real terminal (not the curl|bash pipe). Prints
# the default and uses it automatically if there's no TTY to prompt on.
prompt() {
    local message="$1" default="$2" varname="$3"
    if [ "$HAVE_TTY" = true ]; then
        read -r -p "$message [$default]: " reply < /dev/tty || reply=""
        echo "${reply:-$default}"
    else
        echo "No TTY available -- using default: $default" >&2
        echo "$default"
    fi
}

confirm() {
    local message="$1"
    if [ "$HAVE_TTY" = true ]; then
        read -r -p "$message [Y/n]: " reply < /dev/tty || reply="y"
        [[ "$reply" =~ ^[Nn] ]] && return 1 || return 0
    else
        echo "No TTY available -- proceeding with '$message' by default." >&2
        return 0
    fi
}

log "This will install ntopng, clone HackCheck, and set up router-backend on this machine."
if ! confirm "Continue?"; then
    echo "Aborted."
    exit 0
fi

# Asked explicitly rather than assumed from whoami -- if this script were
# ever run via sudo, whoami would wrongly return root instead of the real
# account that should get Docker/sudoers/group access. Defaults to
# whoami's answer (usually correct), but you can override it.
TARGET_USER="$(prompt "Which username should get Docker/sudoers/group access" "$(whoami)" target_user)"
echo "Setting up for user: $TARGET_USER"

# --- 1 & 2. ntopng (via Docker) + network interface -------------------
# ntopng's native apt packages for Raspberry Pi OS/ARM have been broken
# or missing for years (multiple long-open issues on ntop's own GitHub --
# "Package 'ntopng' has no installation candidate" is a known, common,
# unresolved problem, not something specific to your setup). ntop.org's
# own officially documented fix for Raspberry Pi is their ARM64 Docker
# image instead: https://hub.docker.com/r/ntop/ntopng_arm64.dev
#
# Wrapped in a systemd service below so `systemctl start/stop/is-active
# ntopng` keeps working exactly as ntopng_control.py (and the Android
# app's Start/Stop box buttons) already expect -- nothing about that
# control surface changes, only how ntopng itself actually runs.
log "Which network interface is connected to your switch's mirrored port?"
echo "Available interfaces on this machine:"
DEFAULT_IFACE=""
for iface in /sys/class/net/*; do
    name="$(basename "$iface")"
    [ "$name" = "lo" ] && continue
    echo "  - $name"
    [ -z "$DEFAULT_IFACE" ] && DEFAULT_IFACE="$name"
done
[ -z "$DEFAULT_IFACE" ] && DEFAULT_IFACE="eth0"

SELECTED_IFACE="${HACKCHECK_INTERFACE:-$(prompt "Interface for ntopng to monitor" "$DEFAULT_IFACE" iface)}"
echo "Using interface: $SELECTED_IFACE"

log "Setting up ntopng via Docker..."
ARCH="$(uname -m)"
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
    echo "Detected architecture: $ARCH -- ntop's officially supported Docker image"
    echo "targets 64-bit ARM (aarch64/arm64), i.e. the 64-bit Raspberry Pi OS image."
    echo "32-bit community ntopng images exist but are old and reported unstable."
    echo "Skipping automatic ntopng install -- if you're on 32-bit Raspberry Pi OS,"
    echo "consider reflashing with the 64-bit image, or install ntopng manually."
else
    if ! command -v docker >/dev/null 2>&1; then
        if confirm "Docker isn't installed. Install it now (official get.docker.com script)?"; then
            curl -fsSL https://get.docker.com | sh
            sudo usermod -aG docker "$TARGET_USER"
            echo "Added $TARGET_USER to the docker group -- needs a fresh login (or reboot)"
            echo "before $TARGET_USER can run plain 'docker' commands without sudo. This"
            echo "script uses sudo for its own docker commands below, so it isn't affected"
            echo "by that same limitation."
        fi
    fi

    if command -v docker >/dev/null 2>&1; then
        NTOPNG_IMAGE="ntop/ntopng_arm64.dev:latest"
        # sudo here, not plain docker -- $TARGET_USER was just added to the
        # docker group above, but that membership doesn't apply until a
        # fresh login/new shell. Using sudo means this works immediately
        # in the same script run, regardless of whether $TARGET_USER is
        # the account actually running this script right now.
        sudo docker pull "$NTOPNG_IMAGE"

        sudo tee /etc/systemd/system/ntopng.service > /dev/null << SERVICE_EOF
[Unit]
Description=ntopng (via Docker)
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Restart=on-failure
ExecStartPre=-/usr/bin/docker rm -f ntopng
# --cap-add=NET_ADMIN is required for ntopng to put the mirrored interface
# into promiscuous mode. Without it, Docker's default capability set
# (NET_RAW) lets it capture the host's own traffic fine, but silently
# can't see traffic addressed to other devices -- i.e. the actual point
# of a mirrored port. Confirmed via tcpdump seeing full mirrored traffic
# on the host while ntopng showed zero devices/flows.
ExecStart=/usr/bin/docker run --rm --name ntopng --net=host --cap-add=NET_ADMIN ${NTOPNG_IMAGE} -i ${SELECTED_IFACE}
ExecStop=/usr/bin/docker stop ntopng

[Install]
WantedBy=multi-user.target
SERVICE_EOF

        sudo systemctl daemon-reload
        sudo systemctl enable ntopng
        sudo systemctl restart ntopng
        echo "ntopng service status:"
        sudo systemctl is-active ntopng || echo "(not active yet -- check 'sudo systemctl status ntopng' and 'sudo docker logs ntopng' for details)"
        echo ""
        echo "ntopng web UI (once running): http://<this-box-ip>:3000 (default login admin/admin)"
    else
        echo "Docker not available -- skipping ntopng setup. Install Docker and re-run this step."
    fi
fi

# --- 3. Clone the repo ---------------------------------------------------
log "Checking HackCheck repo..."
if [ -d "$REPO_DIR" ]; then
    echo "$REPO_DIR already exists, skipping clone. (git pull inside it yourself if you want the latest.)"
else
    git clone "$REPO_URL" "$REPO_DIR"
fi

# --- 4. Python venv + deps ----------------------------------------------
log "Setting up router-backend Python environment..."
cd "$REPO_DIR/router-backend"
if [ ! -d ".venv" ]; then
    python3 -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
deactivate

# --- 5. Scoped sudoers for the start/stop/status control endpoints ------
log "Setting up sudoers for ntopng control..."
SYSTEMCTL_PATH="$(command -v systemctl || true)"

if [ -z "$SYSTEMCTL_PATH" ]; then
    echo "Could not find systemctl on PATH -- skipping sudoers setup."
    echo "The app's Start/Stop box buttons won't work until this is set up manually."
else
    SUDOERS_LINE="${TARGET_USER} ALL=(ALL) NOPASSWD: ${SYSTEMCTL_PATH} start ntopng, ${SYSTEMCTL_PATH} stop ntopng, ${SYSTEMCTL_PATH} is-active ntopng"

    TMP_SUDOERS="$(mktemp)"
    echo "$SUDOERS_LINE" > "$TMP_SUDOERS"

    if sudo visudo -c -f "$TMP_SUDOERS" >/dev/null 2>&1; then
        sudo install -m 0440 "$TMP_SUDOERS" "$SUDOERS_FILE"
        echo "Sudoers entry installed at $SUDOERS_FILE for user $TARGET_USER."
    else
        echo "Generated sudoers line failed validation -- NOT installed, to avoid breaking sudo."
        echo "Line that failed: $SUDOERS_LINE"
    fi
    rm -f "$TMP_SUDOERS"
fi

# --- 6. Optional .env setup ----------------------------------------------
log "Set up router-backend/.env now?"
SETUP_ENV="${HACKCHECK_SETUP_ENV:-}"
if [ -z "$SETUP_ENV" ]; then
    if confirm "Walk through Postgres connection details interactively?"; then
        SETUP_ENV="yes"
    else
        SETUP_ENV="no"
    fi
fi

if [ -f ".env" ]; then
    echo ".env already exists, leaving it as-is."
elif [ "$SETUP_ENV" = "yes" ]; then
    DB_HOST="$(prompt "Postgres host" "localhost" host)"
    DB_PORT="$(prompt "Postgres port" "5432" port)"
    DB_NAME="$(prompt "Postgres database name" "hackcheck_router" dbname)"
    DB_USER="$(prompt "Postgres username" "hackcheck" dbuser)"
    if [ "$HAVE_TTY" = true ]; then
        read -r -s -p "Postgres password: " DB_PASSWORD < /dev/tty
        echo ""
    else
        DB_PASSWORD="changeme"
        echo "No TTY available -- leaving password as a placeholder; edit .env manually." >&2
    fi

    # If Postgres is meant to run on this same box, offer to actually
    # install it and create the role/database -- collecting connection
    # details above only builds a connection STRING, it doesn't make
    # anything on the Postgres side actually exist yet.
    if [ "$DB_HOST" = "localhost" ] || [ "$DB_HOST" = "127.0.0.1" ]; then
        if ! command -v psql >/dev/null 2>&1; then
            if confirm "Postgres isn't installed on this box yet. Install it now?"; then
                sudo apt update
                sudo apt install -y postgresql
            fi
        fi

        if command -v psql >/dev/null 2>&1; then
            if confirm "Create the '$DB_USER' role and '$DB_NAME' database now?"; then
                # Single-quote-escape for the SQL literal (doubling embedded
                # single quotes) -- separate from the URL-encoding done
                # below, which is for the connection string, not raw SQL.
                DB_PASSWORD_SQL_ESCAPED="$(printf '%s' "$DB_PASSWORD" | sed "s/'/''/g")"

                sudo -u postgres psql -v ON_ERROR_STOP=1 > /dev/null << SQL_EOF
DO \$\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${DB_USER}') THEN
      CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD_SQL_ESCAPED}';
   END IF;
END
\$\$;
SQL_EOF
                DB_EXISTS="$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'")"
                if [ "$DB_EXISTS" != "1" ]; then
                    sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};" > /dev/null
                    echo "Created database '$DB_NAME' owned by '$DB_USER'."
                else
                    echo "Database '$DB_NAME' already exists, leaving it as-is."
                fi
            fi
        fi
    fi

    cp .env.example .env
    # URL-encode both username and password before building the connection
    # string -- a raw password containing @, :, /, or similar breaks
    # DATABASE_URL parsing (SQLAlchemy misreads the host/port/etc). Encoding
    # with safe='' also makes the result safe to drop straight into sed's
    # replacement string with no separate sed-escaping needed, since
    # everything sed treats specially (&, /, \) gets percent-encoded too.
    DB_USER_ENCODED="$(python3 -c "import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_USER")"
    DB_PASSWORD_ENCODED="$(python3 -c "import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$DB_PASSWORD")"
    sed -i "s|^DATABASE_URL=.*|DATABASE_URL=postgresql://${DB_USER_ENCODED}:${DB_PASSWORD_ENCODED}@${DB_HOST}:${DB_PORT}/${DB_NAME}|" .env
    echo ".env created with your Postgres details. ntopng credentials in it are still placeholders -- edit those once you know them."
else
    cp .env.example .env
    echo ".env created from the template with placeholder values -- edit it before running init_db.py."
fi

log "Creating database tables (init_db.py)..."
if [ -x ".venv/bin/python" ]; then
    # Guarded with if/then rather than run bare -- this script runs under
    # set -e, and a bare failing command here would kill the rest of setup
    # (Pi-hole/Suricata/kiosk/systemd steps below) over something as
    # recoverable as DATABASE_URL still being a placeholder. Report and
    # continue instead.
    if .venv/bin/python init_db.py; then
        echo "Tables created successfully."
    else
        echo "init_db.py failed -- almost certainly DATABASE_URL in .env isn't valid/reachable yet"
        echo "(placeholder values, or Postgres isn't actually running). Fix .env, then run:"
        echo "  cd $(pwd) && source .venv/bin/activate && python init_db.py"
        echo "The systemd services set up later in this script will keep retrying every 5s"
        echo "and self-heal automatically once this succeeds -- no need to restart them by hand."
    fi
else
    echo "No .venv found at .venv/bin/python -- skipping automatic table creation."
fi

# --- 7. Optional: Pi-hole -------------------------------------------------
log "Install Pi-hole for DNS-level visibility?"
if confirm "Install Pi-hole now (runs its own official interactive installer)?"; then
    if command -v pihole >/dev/null 2>&1; then
        echo "Pi-hole already installed, skipping."
    else
        echo "Launching Pi-hole's own installer -- follow its prompts (upstream DNS, interface, etc)."
        curl -sSL https://install.pi-hole.net | bash
        echo ""
        echo "Pi-hole installed. Set an admin/API password if you haven't:"
        echo "  sudo pihole -a -p"
        echo "Then add that password to router-backend/.env as PIHOLE_PASSWORD"
        echo "(or leave PIHOLE_PASSWORD blank in .env if you chose no password)."
    fi
else
    echo "Skipping Pi-hole."
fi

# --- 8. Optional: Suricata -------------------------------------------------
log "Install Suricata for signature-based intrusion detection?"
if confirm "Install Suricata now?"; then
    if command -v suricata >/dev/null 2>&1; then
        echo "Suricata already installed, skipping apt install."
    else
        sudo apt update
        sudo apt install -y suricata
    fi

    SURICATA_DEFAULTS="/etc/default/suricata"
    if [ -f "$SURICATA_DEFAULTS" ]; then
        if grep -q '^IFACE=' "$SURICATA_DEFAULTS" 2>/dev/null; then
            sudo sed -i "s|^IFACE=.*|IFACE=${SELECTED_IFACE}|" "$SURICATA_DEFAULTS"
        else
            echo "IFACE=${SELECTED_IFACE}" | sudo tee -a "$SURICATA_DEFAULTS" > /dev/null
        fi
        echo "Suricata configured to monitor $SELECTED_IFACE (in $SURICATA_DEFAULTS)."
    else
        echo "$SURICATA_DEFAULTS not found -- this differs from the standard Debian/Raspberry"
        echo "Pi OS suricata package layout. Configure the interface manually in"
        echo "/etc/suricata/suricata.yaml under af-packet: interface: before starting it."
    fi

    sudo systemctl enable suricata
    sudo systemctl restart suricata
    echo "Suricata service status:"
    sudo systemctl is-active suricata || echo "(not active yet -- check 'sudo systemctl status suricata' for details)"

    # eve.json is typically owned by a dedicated group -- grant this user
    # read access via that group rather than running the poller as root.
    EVE_JSON_PATH="/var/log/suricata/eve.json"
    if [ -e "$(dirname "$EVE_JSON_PATH")" ]; then
        LOG_GROUP="$(stat -c '%G' "$(dirname "$EVE_JSON_PATH")" 2>/dev/null || echo "")"
        if [ -n "$LOG_GROUP" ] && [ "$LOG_GROUP" != "root" ] && ! id -nG "$TARGET_USER" | grep -qw "$LOG_GROUP"; then
            sudo usermod -aG "$LOG_GROUP" "$TARGET_USER"
            echo "Added $TARGET_USER to group '$LOG_GROUP' for eve.json read access."
            echo "Log out and back in (or reboot) for this to take effect before running suricata_poller.py."
        fi
    fi
else
    echo "Skipping Suricata."
fi

# --- 9. Optional: kiosk mode ------------------------------------------------
log "Set up kiosk mode (boot straight into the dashboard on this Pi's screen)?"
if confirm "Set up kiosk mode now?"; then
    CHROMIUM_PKG=""
    if command -v chromium-browser >/dev/null 2>&1 || apt-cache show chromium-browser >/dev/null 2>&1; then
        CHROMIUM_PKG="chromium-browser"
    elif command -v chromium >/dev/null 2>&1 || apt-cache show chromium >/dev/null 2>&1; then
        CHROMIUM_PKG="chromium"
    fi

    if [ -z "$CHROMIUM_PKG" ]; then
        echo "Could not find a chromium package (checked chromium-browser and chromium)."
        echo "Install one manually and re-run this step, or configure kiosk mode by hand."
    else
        if ! command -v "$CHROMIUM_PKG" >/dev/null 2>&1; then
            sudo apt update
            sudo apt install -y "$CHROMIUM_PKG"
        fi

        KIOSK_URL="$(prompt "URL to display in kiosk mode" "http://localhost:8000/static/dashboard.html" kiosk_url)"

        LABWC_AUTOSTART="$HOME/.config/labwc/autostart"
        mkdir -p "$(dirname "$LABWC_AUTOSTART")"
        KIOSK_LINE="$CHROMIUM_PKG --kiosk --noerrdialogs --disable-infobars --no-first-run --enable-features=OverlayScrollbar --start-maximized \"$KIOSK_URL\" &"

        if [ -f "$LABWC_AUTOSTART" ] && grep -qF -- "--kiosk" "$LABWC_AUTOSTART"; then
            echo "$LABWC_AUTOSTART already has a kiosk entry -- leaving it as-is rather than adding a duplicate."
            echo "Edit it by hand if you want to change the URL: $LABWC_AUTOSTART"
        else
            echo "$KIOSK_LINE" >> "$LABWC_AUTOSTART"
            echo "Added kiosk autostart entry to $LABWC_AUTOSTART"
        fi

        echo ""
        echo "This assumes Raspberry Pi OS's default desktop session (Wayland/labwc, the"
        echo "default on Bookworm and later). If this Pi boots to a login prompt rather"
        echo "than the desktop, enable desktop autologin: sudo raspi-config -> System"
        echo "Options -> Boot / Auto Login -> Desktop Autologin."
        echo ""
        echo "The kiosk will show a connection error until uvicorn is actually running"
        echo "and reachable at $KIOSK_URL -- that's expected until you finish the manual"
        echo "steps below."
    fi
else
    echo "Skipping kiosk mode."
fi

# --- 10. Optional: Hydra ----------------------------------------------------
log "Install Hydra (credential brute-force testing tool)?"
echo "For testing password strength on devices YOU own/administer -- unauthorized"
echo "use against anything else is illegal. Standalone CLI tool, not part of the"
echo "poller/dashboard pipeline built by everything else in this script."
if confirm "Install Hydra now?"; then
    if command -v hydra >/dev/null 2>&1; then
        echo "Hydra already installed, skipping."
    else
        sudo apt update
        sudo apt install -y hydra
    fi
    echo "Installed. Usage: hydra -h"
else
    echo "Skipping Hydra."
fi

# --- 11. Optional: Wireshark -------------------------------------------------
log "Install Wireshark (packet analysis GUI + tshark CLI)?"
if confirm "Install Wireshark now?"; then
    if command -v wireshark >/dev/null 2>&1; then
        echo "Wireshark already installed, skipping apt install."
    else
        # Pre-seed the non-root-capture debconf question so this doesn't
        # hang waiting for an interactive dialog under curl|bash.
        echo "wireshark-common wireshark-common/install-setuid boolean true" | sudo debconf-set-selections
        sudo apt update
        sudo DEBIAN_FRONTEND=noninteractive apt install -y wireshark
    fi

    if ! id -nG "$TARGET_USER" | grep -qw "wireshark"; then
        sudo usermod -aG wireshark "$TARGET_USER"
        echo "Added $TARGET_USER to the 'wireshark' group for non-root packet capture."
        echo "Log out and back in (or reboot) for this to take effect."
    else
        echo "$TARGET_USER is already in the wireshark group."
    fi
    echo "GUI: wireshark  |  CLI: tshark -i <interface>"
else
    echo "Skipping Wireshark."
fi

# --- 12. Optional: systemd services for backend + pollers ------------------
log "Set up systemd services so the backend and pollers run continuously,"
echo "independent of any terminal or SSH session (recommended for a box that"
echo "sits on your network permanently, rather than one you keep terminals"
echo "open for)."
if confirm "Set up systemd services now?"; then
    BACKEND_DIR="$(pwd)"
    VENV_PYTHON="${BACKEND_DIR}/.venv/bin/python"
    VENV_UVICORN="${BACKEND_DIR}/.venv/bin/uvicorn"

    write_service() {
        local name="$1" description="$2" exec_start="$3" extra_after="$4"
        local service_file="/etc/systemd/system/${name}.service"
        if [ -f "$service_file" ]; then
            echo "$service_file already exists, leaving it as-is."
            return
        fi
        sudo tee "$service_file" > /dev/null << SERVICE_EOF
[Unit]
Description=${description}
After=network-online.target postgresql.service${extra_after}
Wants=network-online.target

[Service]
Type=simple
User=${TARGET_USER}
WorkingDirectory=${BACKEND_DIR}
ExecStart=${exec_start}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICE_EOF
        echo "Created $service_file"
    }

    write_service "hackcheck-backend" "HackCheck router-backend (FastAPI)" \
        "${VENV_UVICORN} main:app --host 0.0.0.0 --port 8000" ""
    write_service "hackcheck-ntopng-poller" "HackCheck ntopng poller" \
        "${VENV_PYTHON} poller.py" " ntopng.service"

    # Only create pollers for what's actually installed -- checking the real
    # commands rather than assuming based on earlier prompts, since this step
    # might also run standalone against an already-configured box.
    if command -v pihole >/dev/null 2>&1; then
        write_service "hackcheck-pihole-poller" "HackCheck Pi-hole poller" \
            "${VENV_PYTHON} pihole_poller.py" ""
    fi
    if command -v suricata >/dev/null 2>&1; then
        write_service "hackcheck-suricata-poller" "HackCheck Suricata poller" \
            "${VENV_PYTHON} suricata_poller.py" " suricata.service"
    fi

    sudo systemctl daemon-reload

    SERVICES_TO_START="hackcheck-backend hackcheck-ntopng-poller"
    [ -f /etc/systemd/system/hackcheck-pihole-poller.service ] && SERVICES_TO_START="$SERVICES_TO_START hackcheck-pihole-poller"
    [ -f /etc/systemd/system/hackcheck-suricata-poller.service ] && SERVICES_TO_START="$SERVICES_TO_START hackcheck-suricata-poller"

    echo "Enabling and starting: $SERVICES_TO_START"
    # shellcheck disable=SC2086
    sudo systemctl enable $SERVICES_TO_START
    # shellcheck disable=SC2086
    sudo systemctl start $SERVICES_TO_START

    echo ""
    echo "Status (a service showing 'activating' rather than 'active' may "
    echo "still be waiting on Postgres/init_db.py -- see the manual steps "
    echo "below if so):"
    # shellcheck disable=SC2086
    sudo systemctl status $SERVICES_TO_START --no-pager || true
else
    echo "Skipping systemd setup -- you'll need to run uvicorn/pollers manually"
    echo "each time, in their own terminals, and they'll stop if that session ends."
fi

# --- Summary --------------------------------------------------------------
log "Done. Still manual, in order:"
cat << 'EOF'
  1. init_db.py already ran automatically above. If it reported failure
     (DATABASE_URL wasn't valid/reachable yet at the time), fix .env and
     run it manually -- see the message it printed for the exact command.
     If you set up systemd services below, they'll keep retrying every 5s
     and self-heal automatically once this succeeds, no restart needed.
  2. If you didn't set up Postgres above (e.g. running it on a
     different machine), make sure it's actually running and reachable
  3. Review .env -- especially the ntopng/Pi-hole credentials if you
     didn't set those up interactively
  4. If you did NOT set up systemd services above: run these manually,
     each in its own terminal, and they'll stop when that terminal/SSH
     session ends --
       source .venv/bin/activate
       uvicorn main:app --host 0.0.0.0 --port 8000
       python pihole_poller.py   (if Pi-hole was installed)
       python suricata_poller.py (if Suricata was installed, may need
                                   a fresh login first for eve.json
                                   read access, see the group note
                                   above if it was printed)
     If you DID set up systemd services, these are already running
     continuously -- check with:
       sudo systemctl status hackcheck-backend hackcheck-ntopng-poller
  5. Point the HackCheck Android app's Router screen at this box's
     LAN IP, e.g. http://<this-box-ip>:8000, or open
     http://<this-box-ip>:8000/static/dashboard.html in any browser
EOF
