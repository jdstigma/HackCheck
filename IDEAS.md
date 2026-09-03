# HackCheck — Ideas Backlog

A running list of feature/enhancement ideas to review one by one. Status legend:
`💡 proposed` · `👍 approved` · `🔨 in progress` · `✅ done` · `❄️ parked` · `🗑️ dropped`

---

## Under review

| # | Idea | Notes | Status |
|---|------|-------|--------|
| 1 | **Potential Play Store Publication** | Planned as a paid ($0.99) listing. Full step list: (1) create/verify developer Google account, (2) pay $25 one-time registration fee, (3) complete identity verification (ID + payment card + profile must match), (4) set up Google Payments merchant account (bank + tax info) for payouts — **user completing 1-4 themselves**, will return to pick this up once done. Then: (5) write + host a public privacy policy (Claude can draft content, needs a hosted URL e.g. GitHub Pages), (6) fill out Play Console's Data Safety form (must stay consistent with privacy policy, especially once packet capture is in the app), (7) store listing assets (description, screenshots, feature graphic, content rating questionnaire), (8) upload signed release build + submit, (9) mandatory closed testing — 12 opted-in testers continuously for 14 days before production release is allowed, (10) review itself — biggest risk is Google's Stalkerware and Monitoring Applications policy given this app's permission set + planned packet capture; needs explicit self-protection framing ("check YOUR OWN device"), not monitoring-another-person framing, to have a real shot at passing. | 💡 proposed — blocked on user completing steps 1-4 |
| 3 | **Dedicated "PCAP + TLS decryption + monitor" page** | Its own screen (matching the Scan / Network / Monitor split), combining packet capture with TLS decryption (à la PCAPdroid's MITM module — local CA cert install) so HTTPS payloads are readable, not just metadata. Investigate viability first: local CA cert install UX/security implications, whether apps with cert pinning defeat it anyway (same limitation hit with TextNow earlier), and how this folds into the Play Store stalkerware-policy framing from #1. | 💡 proposed |
| 4 | **Hydra-style network login brute-forcer** (SSH/FTP/HTTP-form password guessing against a live remote service) | Explicitly deferred, not yet approved — user wants to decide later whether it actually has a purpose for this app. Meaningfully different category from the local hash tools already built: this attacks a live remote system's login rather than doing local math on a hash already in hand, so needs clear authorization context (pentest engagement, CTF, own systems) before building, same bar as hashcat got. | 💡 proposed — purpose TBD, revisit later |
| 6 | **Fix router-backend's poller.py against ntopng's REST API** | Dashboard/topology's Devices, Flows, and Top Talker cards stay at zero — not a bug in our code. Root-caused thoroughly (2026-09-03): our endpoint/params/session-auth are all confirmed correct against ntop's own official docs; ntopng's own web UI shows real flow/host data fine; the specific failure is `/lua/rest/v2/get/flow/active.lua` closing the connection mid-response (`RemoteDisconnected`) on ntopng's `_arm64.dev` (nightly) image — reproduced identically across two nightly builds a month apart (`20260707` and `20260805` commits), so it's a real, currently-unpatched bug in ntop's own dev branch, not fixable from our side. The stable `ntop/ntopng` image is amd64-only (confirmed via Docker Hub), so there's no escape to a non-nightly build on this ARM64 Pi. **User wants this addressed soon — the dashboard is an important part of the hardware route, not just nice-to-have.** In the meantime, a Quick Link to ntopng's own UI was added to the app's Router screen so the underlying flow/host data is still reachable directly. Next things worth trying: periodically re-pull `:latest` to check for a fix in a newer nightly (worth a routine check, cheap to do), or file/search ntop's GitHub issues for this specific regression. | 💡 proposed — user wants this soon, real upstream bug confirmed |

---

## Approved / in progress

_(moved here when picked up)_

---

## Done

- Packet capture, Option A (connection-metadata capture) — `VpnService` + hand-rolled per-flow socket relay (UDP + simplified TCP, no third-party TCP stack). Investigation ruled out bundling PCAPdroid's engine (GPL-3.0, would force HackCheck to also be GPL, undercutting a paid listing) and root-based capture; go-tun2socks/hev-sockstun noted as a fallback upgrade if the hand-rolled TCP relay proves unreliable in practice. Own dedicated screen (`Screen.Capture`). Built, fixed two real bugs (OOM from unbounded per-flow threads; TCP handshake race sending SYN-ACK before the backend connected). **Currently hidden from the UI** (`CAPTURE_FEATURE_ENABLED = false` in `Screen.kt`) — real-device testing hit a confirmed, currently-unpatched **Android 16 platform bug**: `VpnService.protect()` returns `false` for every socket, so our relay's own outbound connections loop back into our own tunnel instead of reaching the real network. Same bug affects Proton VPN/Mullvad/WireGuard/TunnelBear (Google issue tracker, filed 2025-08, no fix as of 2026-07-30). Reboot, full uninstall+reinstall, and a full power-cycle were all tried on the test device — none cleared it. UDP relay is confirmed working (real bytes transferred); TCP relay logic is believed correct but untestable until `protect()` works again. Re-enable by flipping the flag once confirmed fixed (device update, or validated on a different unaffected device).
- `analyze_capture.py` — PC-side breakdown of exported capture logs: per-app/per-remote pivots, distinct-destination fan-out flagging, unattributed ("Unknown" app) flow highlighting, protocol split, charts. Verified end-to-end against synthetic data.
- v1: one-shot scan (hidden apps, dangerous permission grants, known-stalkerware list, dual-use monitoring list, accessibility services, device admin/owner status)
- v2: per-app data usage (NetworkStatsManager + Usage Access), paired Bluetooth devices, current WiFi connection, current cell service state
- v3: background monitoring service (foreground service, persistent notification by OS requirement) — event-driven cell-service-state + WiFi-connection logging
- v4: boot-persistence (BootReceiver + MonitorPrefs) so monitoring survives a reboot if it was running
- Configurable snapshot interval (5/15/30/60 min) with periodic installed-app + Bluetooth diffing and heartbeat entries; added "Off" option to disable periodic snapshots entirely (event-driven only)
- Split single scrolling screen into a home menu + 3 independent screens (Scan / Network & Devices / Monitoring), each with its own export
- Two-line layout for the monitoring interval button row
- Icon redesign: replaced magnifying-glass foreground (read as "stalker app") with a checkmark (protective/verified framing)
- Network Tools screen: in-app CLI (`Screen.Tools`) with no-root recon commands — `ping`/`dns`/`portscan`/`myip`, netcat-style `nc`/`ncudp`/`nclisten`/`ncudplisten` (display-only, deliberately no `-e /bin/sh`-style execution)
- Hash tools in the same CLI: `hash`/`hashid`/`crack`/`bruteforce` (pure Kotlin `MessageDigest`, not a hashcat port — no GPU access on Android — scoped with a length-6 cap, 50M-combination ceiling, 60s timeout) plus base64/hex/URL encode-decode
- PC-side MVT + ALEAPP forensics companion (`analysis/forensics/`) — setup script (isolated venvs per tool, since MVT and ALEAPP pin conflicting `packaging` versions — confirmed by actually running the installer), AndroidQF acquisition workflow, corrected ALEAPP CLI flags after verifying against its actual `--help` output. MVT successfully downloaded its real IOC set including a dedicated Stalkerware indicators feed. In-app `forensics` command points to the PC-side workflow (these are Python tools, can't run inside the Android app itself). Push-button `run_forensics.ps1` runs acquisition + MVT scan in one step.
- Standalone PC companion app (`hackcheck_companion.py`, matching TraceWorthy's launcher pattern) — tkinter Run/Help tabs wrapping `analyze_scan.py` (generalized from the earlier ad-hoc script — network/inventory/findings pivots + chart) and `analyze_capture.py` in-process, plus buttons that shell out to the forensics setup/run PowerShell scripts. Packaged via PyInstaller (`build_exe.bat`) into a onefile `.exe`, with a double-click `HackCheck Companion.bat` wrapper for running from source.
- Signed release build (2026-09-03) — dedicated release keystore (`hackcheck-release.keystore`, gitignored, RSA 2048, 10000-day validity) with `signingConfigs.release` reading credentials from `local.properties` (same gitignored-secrets pattern as the OpenCellID key) so a fresh checkout without it still builds debug fine. Bumped to v1.1.0 (from the existing July `v1.0.0` tag, which pointed at a much older commit) given how much shipped since then. Published as a GitHub Release with the signed APK attached: https://github.com/jdstigma/HackCheck/releases/tag/v1.1.0. **Keystore + `local.properties` need a durable backup (password manager / secure cloud storage) — losing either means future releases can never be signed to match this app's identity again.**

---

## Parked / dropped

_(none yet)_
