# PC-side forensics companion: MVT + ALEAPP

MVT (Mobile Verification Toolkit, by Amnesty International's Security Lab)
and ALEAPP (Android Logs Events And Protobuf Parser) are **PC-side Python
tools**, not Android apps -- they analyze artifacts pulled *from* a phone
(a backup, a bugreport, intrusion logs), they don't run *on* the phone. That's
why this lives here as a companion, separate from the HackCheck app itself,
the same way `analyze_capture.py` and `analyze_hackcheck.py` (in the parent
`analysis/` folder) are PC-side breakdowns of what the app exports.

## What each tool does

- **MVT** — the actively-maintained standard for "was this specific device
  hit by known spyware" investigations. Checks device artifacts against
  published indicators of compromise (IOCs) — including a dedicated
  **Stalkerware indicators** feed, plus known spyware families like NSO
  Pegasus, Predator, Candiru, NoviSpy, and others. New in 2026: a
  `check-advanced-logs` module specifically for Android's **Intrusion
  Logging** feature (Settings → Security & privacy → Advanced Protection →
  Intrusion Logging — requires Advanced Protection Mode turned on *before*
  a suspected compromise; logs can't be gathered retroactively). This is
  the more realistic tool for a non-rooted ADB-only acquisition (see the
  ALEAPP caveat below).
- **ALEAPP** — a broader, general-purpose Android artifact parser (call
  logs, app usage, WiFi/Bluetooth history, and much more), widely used in
  digital forensics. Not spyware-specific like MVT. **Real limitation**:
  most of its parser modules expect a genuine filesystem-level extraction
  (root, or a full physical image) — the kind of paths you only get with
  root access. A non-rooted ADB acquisition won't have much for most
  modules to parse; treat it as a bonus pass, not the primary tool here.
- **AndroidQF** — the acquisition tool that actually pulls data off the
  device (bugreport, ADB backup, system logs, intrusion logs if available)
  into a structured folder that MVT can analyze directly.

## One-time setup

```powershell
powershell -ExecutionPolicy Bypass -File setup.ps1
```

Creates two **separate virtual environments** (`.venv-mvt` and
`.venv-aleapp`) and installs each tool into its own — MVT and ALEAPP pin
conflicting versions of the `packaging` dependency, so sharing one
environment leaves one of them on a version its own requirements say is
incompatible (confirmed by actually running the installer: pip's resolver
flags it as a real conflict). Also downloads MVT's current IOC set
(`download-iocs`), and reminds you to grab the AndroidQF binary manually
(it's a compiled release, not a pip package):
https://github.com/mvt-project/androidqf/releases — download the Windows
`.exe` and save it into this folder.

## Workflow

**1. Acquire data from the device** (phone connected via USB, debugging
enabled, same as everything else in this repo):

```powershell
.\androidqf.exe
```

It's interactive -- follow the prompts. It'll ask about backup scope ("Only
SMS" / "Everything" / "No backup" -- "Everything" only backs up apps that
explicitly allow it) and will pull a bugreport, system logs, and intrusion
logs if the device has Advanced Protection Mode + Intrusion Logging enabled.
Output lands in a timestamped folder.

**2. Analyze with MVT:**

```powershell
.\.venv-mvt\Scripts\mvt-android.exe check-androidqf <the-output-folder> -o mvt_results
```

MVT auto-detects the AndroidQF folder structure (including an
`intrusion-logs/` subfolder if present) and runs its full check suite,
including `check-advanced-logs` if intrusion logs were collected.

**3. Analyze with ALEAPP** (bonus pass, same acquisition — see the
limitation noted above):

```powershell
.\.venv-aleapp\Scripts\python.exe ALEAPP\aleapp.py -t fs -i <the-output-folder> -o aleapp_results
```

`-t fs` treats the input as a folder of extracted files. AndroidQF's output
isn't a true filesystem-layout extraction, so expect most modules to come
up empty on a non-rooted acquisition — this is an inherent limitation of
no-root acquisition, not a bug in ALEAPP. If AndroidQF produced an `adb
backup` (`.ab`) file and you want to try that route instead, it needs
converting to a standard `.tar` first (e.g. with
`android-backup-extractor`/`abe.jar`) before `aleapp.py -t tar` can read it.

## Handling the output

**`androidqf_output/`, `mvt_results/`, and `aleapp_results/` are gitignored**
in this repo (see `.gitignore`) -- this data can include real personal
information from the acquired device. Never commit it. Review locally only.
