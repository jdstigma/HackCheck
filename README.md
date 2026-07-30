# HackCheck

A lightweight Android app that scans the device it's installed on for signs of
covert monitoring or compromise: hidden apps, apps holding sensitive
permissions, active accessibility services, device admin/owner apps, and
matches against a small list of publicly documented stalkerware package
names.

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

Also **not retroactively available** — Android doesn't expose a history of
these to apps, only current state:
- **Cell-service-drop history** (when/how often total loss of service
  happened) — only a live listener running continuously could log this,
  and only from whenever it starts forward, never retroactively.
- **WiFi connection history** (which networks connected/disconnected and
  when) — same limitation; only current connection is queryable.

Both would need a persistent background-monitoring service (with the
foreground-service notification Android requires for that), planned as a
separate follow-up rather than part of the one-shot scan model above.

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
