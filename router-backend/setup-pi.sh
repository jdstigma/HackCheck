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
#
# Still manual after this (needs decisions only you can make):
#   - Actually setting up Postgres itself (local install or Docker)
#   - Switch port mirroring (that's switch-side, not this box)
#   - Running init_db.py and starting uvicorn
#   - If Pi-hole/Suricata were installed: their own poller scripts
#     (pihole_poller.py, suricata_poller.py) still need to be started
#     separately, same as poller.py for ntopng

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
        if [ -n "$LOG_GROUP" ] && [ "$LOG_GROUP" != "root" ] && ! id -nG "$CURRENT_USER" | grep -qw "$LOG_GROUP"; then
            sudo usermod -aG "$LOG_GROUP" "$CURRENT_USER"
            echo "Added $CURRENT_USER to group '$LOG_GROUP' for eve.json read access."
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

    if ! id -nG "$CURRENT_USER" | grep -qw "wireshark"; then
        sudo usermod -aG wireshark "$CURRENT_USER"
        echo "Added $CURRENT_USER to the 'wireshark' group for non-root packet capture."
        echo "Log out and back in (or reboot) for this to take effect."
    else
        echo "$CURRENT_USER is already in the wireshark group."
    fi
    echo "GUI: wireshark  |  CLI: tshark -i <interface>"
else
    echo "Skipping Wireshark."
fi

# --- Summary --------------------------------------------------------------
log "Done. Still manual, in order:"
cat << 'EOF'
  1. Make sure Postgres itself is actually running (local install or Docker)
  2. cd HackCheck/router-backend (if not already there)
  3. Review .env -- especially the ntopng/Pi-hole credentials if you
     didn't set those up interactively
  4. source .venv/bin/activate
  5. python init_db.py
  6. uvicorn main:app --reload --host 0.0.0.0 --port 8000
  7. If Pi-hole was installed: python pihole_poller.py (separate terminal)
  8. If Suricata was installed: python suricata_poller.py (separate
     terminal -- may need a fresh login first, see the group note above
     if it was printed)
  9. Point the HackCheck Android app's Router screen at this box's
     LAN IP, e.g. http://<this-box-ip>:8000, or open
     http://<this-box-ip>:8000/static/dashboard.html in any browser
EOF
