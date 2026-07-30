# HackCheck — Ideas Backlog

A running list of feature/enhancement ideas to review one by one. Status legend:
`💡 proposed` · `👍 approved` · `🔨 in progress` · `✅ done` · `❄️ parked` · `🗑️ dropped`

---

## Under review

| # | Idea | Notes | Status |
|---|------|-------|--------|
| 1 | **Potential Play Store Publication** | Planned as a paid ($0.99) listing. Full step list: (1) create/verify developer Google account, (2) pay $25 one-time registration fee, (3) complete identity verification (ID + payment card + profile must match), (4) set up Google Payments merchant account (bank + tax info) for payouts — **user completing 1-4 themselves**, will return to pick this up once done. Then: (5) write + host a public privacy policy (Claude can draft content, needs a hosted URL e.g. GitHub Pages), (6) fill out Play Console's Data Safety form (must stay consistent with privacy policy, especially once packet capture is in the app), (7) store listing assets (description, screenshots, feature graphic, content rating questionnaire), (8) upload signed release build + submit, (9) mandatory closed testing — 12 opted-in testers continuously for 14 days before production release is allowed, (10) review itself — biggest risk is Google's Stalkerware and Monitoring Applications policy given this app's permission set + planned packet capture; needs explicit self-protection framing ("check YOUR OWN device"), not monitoring-another-person framing, to have a real shot at passing. | 💡 proposed — blocked on user completing steps 1-4 |
| 2 | **Investigate all potential packet-capture options** | Research pass before committing to an architecture. Known option so far: `VpnService` + per-flow socket relay (NetGuard/PCAPdroid-style) for connection-metadata capture, vs. full byte-level pcap on top of that. Worth also checking: licensing constraints if referencing/bundling existing open-source engines (PCAPdroid is GPL — bundling GPL code into a paid closed-ish listing creates real obligations), any other Android-native approaches, and realistic effort/risk for each. | 💡 proposed |
| 3 | **Dedicated "PCAP + TLS decryption + monitor" page** | Its own screen (matching the Scan / Network / Monitor split), combining packet capture with TLS decryption (à la PCAPdroid's MITM module — local CA cert install) so HTTPS payloads are readable, not just metadata. Investigate viability first: local CA cert install UX/security implications, whether apps with cert pinning defeat it anyway (same limitation hit with TextNow earlier), and how this folds into the Play Store stalkerware-policy framing from #1. | 💡 proposed |

---

## Approved / in progress

_(moved here when picked up)_

---

## Done

- v1: one-shot scan (hidden apps, dangerous permission grants, known-stalkerware list, dual-use monitoring list, accessibility services, device admin/owner status)
- v2: per-app data usage (NetworkStatsManager + Usage Access), paired Bluetooth devices, current WiFi connection, current cell service state
- v3: background monitoring service (foreground service, persistent notification by OS requirement) — event-driven cell-service-state + WiFi-connection logging
- v4: boot-persistence (BootReceiver + MonitorPrefs) so monitoring survives a reboot if it was running
- Configurable snapshot interval (5/15/30/60 min) with periodic installed-app + Bluetooth diffing and heartbeat entries; added "Off" option to disable periodic snapshots entirely (event-driven only)
- Split single scrolling screen into a home menu + 3 independent screens (Scan / Network & Devices / Monitoring), each with its own export
- Two-line layout for the monitoring interval button row
- Icon redesign: replaced magnifying-glass foreground (read as "stalker app") with a checkmark (protective/verified framing)

---

## Parked / dropped

_(none yet)_
