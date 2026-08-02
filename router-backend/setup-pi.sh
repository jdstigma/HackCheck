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
#   1. Installs ntopng via apt, enables + starts it as a systemd service
#   2. Asks which network interface is connected to the mirrored port,
#      and configures ntopng to listen on it
#   3. Clones the HackCheck repo (skips if already present)
#   4. Sets up router-backend's Python venv + dependencies
#   5. Writes a scoped sudoers drop-in so router-backend can start/stop/
#      check ntopng via the app -- validated with visudo -c before
#      installing, never edits /etc/sudoers directly
#   6. Optionally walks through creating router-backend/.env
#      interactively (Postgres connection details)
#
# Still manual after this (needs decisions only you can make):
#   - Actually setting up Postgres itself (local install or Docker)
#   - Switch port mirroring (that's switch-side, not this box)
#   - Running init_db.py and starting uvicorn

set -euo pipefail

REPO_URL="https://github.com/jdstigma/HackCheck.git"
REPO_DIR="HackCheck"
SUDOERS_FILE="/etc/sudoers.d/hackcheck-ntopng"
NTOPNG_CONF="/etc/ntopng/ntopng.conf"

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

# --- 1. ntopng ---------------------------------------------------------
log "Checking ntopng..."
if command -v ntopng >/dev/null 2>&1; then
    echo "ntopng already installed, skipping apt install."
else
    sudo apt update
    sudo apt install -y ntopng
fi

# --- 2. Network interface --------------------------------------------
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

sudo mkdir -p "$(dirname "$NTOPNG_CONF")"
if [ -f "$NTOPNG_CONF" ] && grep -q '^-i=' "$NTOPNG_CONF" 2>/dev/null; then
    sudo sed -i "s|^-i=.*|-i=${SELECTED_IFACE}|" "$NTOPNG_CONF"
else
    echo "-i=${SELECTED_IFACE}" | sudo tee -a "$NTOPNG_CONF" > /dev/null
fi
echo "ntopng configured to monitor $SELECTED_IFACE (in $NTOPNG_CONF)."

sudo systemctl enable ntopng
sudo systemctl restart ntopng
echo "ntopng service status:"
sudo systemctl is-active ntopng || echo "(not active yet -- check 'sudo systemctl status ntopng' for details)"

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
CURRENT_USER="$(whoami)"

if [ -z "$SYSTEMCTL_PATH" ]; then
    echo "Could not find systemctl on PATH -- skipping sudoers setup."
    echo "The app's Start/Stop box buttons won't work until this is set up manually."
else
    SUDOERS_LINE="${CURRENT_USER} ALL=(ALL) NOPASSWD: ${SYSTEMCTL_PATH} start ntopng, ${SYSTEMCTL_PATH} stop ntopng, ${SYSTEMCTL_PATH} is-active ntopng"

    TMP_SUDOERS="$(mktemp)"
    echo "$SUDOERS_LINE" > "$TMP_SUDOERS"

    if sudo visudo -c -f "$TMP_SUDOERS" >/dev/null 2>&1; then
        sudo install -m 0440 "$TMP_SUDOERS" "$SUDOERS_FILE"
        echo "Sudoers entry installed at $SUDOERS_FILE for user $CURRENT_USER."
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

    cp .env.example .env
    ESCAPED_PASSWORD="$(printf '%s' "$DB_PASSWORD" | sed 's/[&/\]/\\&/g')"
    sed -i "s|^DATABASE_URL=.*|DATABASE_URL=postgresql://${DB_USER}:${ESCAPED_PASSWORD}@${DB_HOST}:${DB_PORT}/${DB_NAME}|" .env
    echo ".env created with your Postgres details. ntopng credentials in it are still placeholders -- edit those once you know them."
else
    cp .env.example .env
    echo ".env created from the template with placeholder values -- edit it before running init_db.py."
fi

# --- Summary --------------------------------------------------------------
log "Done. Still manual, in order:"
cat << 'EOF'
  1. Make sure Postgres itself is actually running (local install or Docker)
  2. cd HackCheck/router-backend (if not already there)
  3. Review .env -- especially the ntopng credentials if you didn't set
     those up interactively
  4. source .venv/bin/activate
  5. python init_db.py
  6. uvicorn main:app --reload --host 0.0.0.0 --port 8000
  7. Point the HackCheck Android app's Router screen at this box's
     LAN IP, e.g. http://<this-box-ip>:8000
EOF
