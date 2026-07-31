# HackCheck

A lightweight Android app that scans the device it's installed on for signs of
covert monitoring or compromise: hidden apps, apps holding sensitive
permissions, active accessibility services, device admin/owner apps, and
matches against a small list of publicly documented stalkerware package
names.

The home screen is a menu into three independent screens, each with its own
export: **App Scan**, **Network & Devices**, and **Background Monitoring**.
Each screen's state persists while you navigate between the others.

## What it checks

- **Installed apps with no home-screen icon** (excluding normal system
  components) — a common self-hiding trick used by stalkerware.
- **Apps holding sensitive granted permissions** (camera, microphone, SMS,
  call log, location, contacts, overlay/"draw over other apps", usage
  access) — especially when combined with a hidden icon.
- **Known-stalkerware package name matches** — a small, best-effort,
  **not exhaustive** list of publicly documented spyware package
  identifiers. A non-match does **not** mean the device is clean.
- **Dual-use monitoring apps** (parental-control / family-location apps
  that are legitimate but can also be used for covert monitoring of an
  adult without consent) — flagged separately from actual malware.
- **Active accessibility services** — powerful (can read screen content and
  simulate input); always listed for manual review since many legitimate
  tools use them too.
- **Active device admins**, and any app holding **Device Owner / Profile
  Owner** status — unusual outside a corporate-managed phone, and grants
  broad remote-management control.
- **Per-app data usage** (WiFi + mobile, last 7 days) — via `NetworkStatsManager`,
  requires granting "Usage access" in Settings (the app links you there directly).
  Mobile-data usage may be unavailable on some devices/Android versions (needs
  carrier-privileged access on some builds) — WiFi usage still works either way.
- **Paired Bluetooth devices** — a snapshot of bonded devices, not just
  currently-connected ones, since a device doesn't need to be actively
  connected right now to have been used for syncing/exfiltration in the past.
- **Current WiFi connection** (SSID/BSSID/link speed) and **current cellular
  service state** (in service / out of service / emergency only / radio off).

Export writes three CSVs to Downloads (findings, full app inventory, and
network/device data) so results can be pulled off the device with `adb pull`
and analyzed on a computer.

## What it can't check (and why)

Android does not let a regular, non-root, third-party app:
- Read another app's granted **AppOps** state directly (e.g., who currently
  holds the "draw over other apps" op) — that requires a signature/system
  permission.
- Read the system-wide list of **trusted CA certificates**. The app will
  point you to check this by hand: **Settings → Security (or Encryption &
  credentials) → Trusted credentials → User tab.** Anything there you didn't
  install yourself is worth investigating — it can indicate traffic
  interception/monitoring.

For deeper visibility (AppOps, full accessibility/admin state, hidden-app
detection with more certainty), a PC-side ADB diagnostic is more thorough
than anything installable on the phone itself, since `adb shell` runs with
elevated shell-user privileges a regular app doesn't have.

## Background monitoring (cell-service + WiFi history)

Android doesn't expose a history of cell-service drops or WiFi connection
changes to apps — only current state. The only way to get this is a service
that listens continuously and logs transitions **from whenever it's started,
forward** — nothing before that point is recoverable.

Pick a snapshot interval (**Off** / 5 / 15 / 30 / 60 min) and tap **"Start
background monitoring"** to run this. **Off** disables periodic snapshots
entirely and only logs event-driven changes (cell-service transitions, WiFi
connect/disconnect) as they happen — no periodic app/Bluetooth diffing or
heartbeat entries. It logs:
- Cell-service-state transitions (in service / out of service / emergency
  only / radio off) via `TelephonyCallback` (Android 12+) or the legacy
  `PhoneStateListener` (older) — event-driven, logged the moment they happen.
- WiFi connect/disconnect events (SSID + BSSID) via a broadcast receiver —
  also event-driven.
- **Periodic snapshots** at the chosen interval (5 min floor): diffs the
  installed non-system app list and paired Bluetooth device list against the
  previous snapshot, logging only what changed (installed/removed apps,
  paired/unpaired devices), plus a heartbeat entry each interval (cell +
  WiFi state) so the log itself proves monitoring was alive at that time —
  useful for spotting a gap where it silently died.

Logged to a simple append-only CSV in app-private storage (no database
dependency), viewable in-app and exportable to Downloads.

**Hard Android constraint: this requires a persistent, visible notification**
while running. Foreground services cannot run hidden — that's intentional on
Android's part, to stop apps from silently monitoring in the background
without the phone's user knowing. There's no way around this.

**Survives a reboot**: `BootReceiver` listens for `BOOT_COMPLETED` and
restarts monitoring automatically, but only if it was actually running (and
not explicitly stopped) beforehand — tracked in `MonitorPrefs`
(SharedPreferences), independent of the OS's own `START_STICKY` service
restart behavior which doesn't survive a full reboot on its own.

## Network Tools (in-app CLI)

A small terminal-style screen for no-root network recon, useful for checking
whether the network you're on looks trustworthy:

- `ping <host> [count]` — via the device's own system `ping` binary
  (`/system/bin/ping`, shelled out to — no raw-socket permission needed,
  same mechanism most Play Store network-utility apps use)
- `dns <host>` — resolve a hostname (`InetAddress.getAllByName`)
- `portscan <host> <start> <end>` — TCP connect scan, capped at 1024 ports
  per run
- `myip` — local network interfaces + WiFi gateway/DNS servers
- `help` — command list

All pure standard-library/Android APIs, no elevated access. Only use against
hosts and networks you're authorized to test.

## Build

Standard Android Gradle project (Kotlin + Jetpack Compose, no third-party
dependencies beyond AndroidX). Open in Android Studio, or:

```
./gradlew :app:assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Sideload it, or
install via `adb install`.

## Disclaimer

This is a personal diagnostic tool, not a security product. It surfaces
signals for you to review yourself, not a verdict — a clean scan is not
proof a device is clean, and a flagged item is not proof of compromise.
When in doubt about a specific finding, treat it as a starting point for
further investigation, not a conclusion.
