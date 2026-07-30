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
