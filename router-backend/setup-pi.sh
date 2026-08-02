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
# What this does:
#   1. Installs ntopng via apt, enables + starts it as a systemd service
#   2. Clones the HackCheck repo (skips if it's already present in the
#      current directory)
#   3. Sets up a Python venv for router-backend and installs its
#      dependencies
#   4. Writes a scoped sudoers drop-in so router-backend can start/stop/
#      check ntopng via the app -- validated with visudo -c before
#      installing, and never edits /etc/sudoers directly
#
# What this does NOT do (deliberately manual steps, since they need
# real secrets/decisions from you):
#   - Configure Postgres or router-backend's .env
#   - Set up switch port mirroring (that's switch-side, not this box)
#   - Run init_db.py or start uvicorn
#
# Safe to re-run -- every step checks whether it's already done first.

set -euo pipefail

REPO_URL="https://github.com/jdstigma/HackCheck.git"
REPO_DIR="HackCheck"
SUDOERS_FILE="/etc/sudoers.d/hackcheck-ntopng"

log() { echo -e "\n==> $1"; }

# --- 1. ntopng ---------------------------------------------------------
log "Checking ntopng..."
if command -v ntopng >/dev/null 2>&1; then
    echo "ntopng already installed, skipping apt install."
else
    sudo apt update
    sudo apt install -y ntopng
fi

sudo systemctl enable ntopng
sudo systemctl start ntopng
echo "ntopng service status:"
sudo systemctl is-active ntopng || echo "(not active yet -- check 'sudo systemctl status ntopng' for details)"

# --- 2. Clone the repo ---------------------------------------------------
log "Checking HackCheck repo..."
if [ -d "$REPO_DIR" ]; then
    echo "$REPO_DIR already exists, skipping clone. (git pull inside it yourself if you want the latest.)"
else
    git clone "$REPO_URL" "$REPO_DIR"
fi

# --- 3. Python venv + deps ----------------------------------------------
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

# --- 4. Scoped sudoers for the start/stop/status control endpoints ------
log "Setting up sudoers for ntopng control..."
SYSTEMCTL_PATH="$(command -v systemctl)"
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

# --- Summary --------------------------------------------------------------
log "Done. Still manual, in order:"
cat << 'EOF'
  1. Set up Postgres (local install or Docker) if you haven't already
  2. cd HackCheck/router-backend
  3. cp .env.example .env, then edit it with your real DATABASE_URL
     and (once you know it) your ntopng credentials
  4. source .venv/bin/activate
  5. python init_db.py
  6. uvicorn main:app --reload --host 0.0.0.0 --port 8000
  7. Point the HackCheck Android app's Router screen at this box's
     LAN IP, e.g. http://<this-box-ip>:8000
EOF
