"""
ntopng_control.py -- start/stop/status for the local ntopng systemd service.

IMPORTANT ASSUMPTION: this only works when router-backend (this FastAPI
app) runs on the SAME machine as ntopng -- e.g. both on the same
Raspberry Pi plugged into your switch's mirrored port. It has no way to
control ntopng on a different machine.

REQUIRES passwordless sudo for exactly these two commands, configured on
the box running this backend. Do NOT grant broad sudo access for this --
scope it tightly. On Debian/Ubuntu/Raspberry Pi OS, run `sudo visudo` and
add a line like (replace `pi` with whatever user runs uvicorn):

    pi ALL=(ALL) NOPASSWD: /usr/bin/systemctl start ntopng, /usr/bin/systemctl stop ntopng, /usr/bin/systemctl is-active ntopng

Confirm the actual path to systemctl on your system first with `which
systemctl` -- it's usually /usr/bin/systemctl or /bin/systemctl, but
verify rather than assume.

If sudo/systemctl aren't available at all (e.g. running this on Windows
during development, or ntopng isn't installed as a systemd service),
every function here returns a clear error rather than crashing.
"""

import subprocess

SYSTEMCTL_TIMEOUT_SECONDS = 10


def _run_systemctl(action: str) -> dict:
    """action is one of: start, stop, is-active"""
    try:
        result = subprocess.run(
            ["sudo", "systemctl", action, "ntopng"],
            capture_output=True,
            text=True,
            timeout=SYSTEMCTL_TIMEOUT_SECONDS,
        )
        return {
            "ok": result.returncode == 0,
            "returncode": result.returncode,
            "stdout": result.stdout.strip(),
            "stderr": result.stderr.strip(),
        }
    except FileNotFoundError:
        # sudo or systemctl itself isn't on PATH -- e.g. not running on
        # Linux, or ntopng isn't managed as a systemd service here.
        return {
            "ok": False,
            "returncode": None,
            "stdout": "",
            "stderr": "sudo/systemctl not found on this system -- this "
                      "backend must run on the same Linux box as ntopng, "
                      "with systemd managing it.",
        }
    except subprocess.TimeoutExpired:
        return {
            "ok": False,
            "returncode": None,
            "stdout": "",
            "stderr": f"systemctl {action} ntopng timed out after "
                      f"{SYSTEMCTL_TIMEOUT_SECONDS}s",
        }


def start_ntopng() -> dict:
    return _run_systemctl("start")


def stop_ntopng() -> dict:
    return _run_systemctl("stop")


def ntopng_status() -> dict:
    """systemctl is-active returns exit code 0 with stdout 'active' when
    running, non-zero with stdout like 'inactive'/'failed' otherwise --
    that non-zero exit is expected and not itself an error condition."""
    result = _run_systemctl("is-active")
    active = result["stdout"] == "active"
    return {
        "active": active,
        "raw_status": result["stdout"] or result["stderr"],
    }
