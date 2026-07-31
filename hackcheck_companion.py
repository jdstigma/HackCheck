"""
hackcheck_companion.py -- a simple desktop control panel for the HackCheck PC-side tools.

Runs on your PC. Gives you a tabbed window with buttons instead of typing:

  Run tab:
    1. Analyze a scan export (network/inventory/findings CSVs) -> analyze_scan.analyze()
    2. Analyze a traffic capture export (capture log CSV)       -> analyze_capture.analyze()
    3. Set up forensics tools (MVT + ALEAPP, one-time)          -> forensics/setup.ps1
    4. Run device forensics: acquire + scan (push-button)       -> forensics/run_forensics.ps1
  Help tab:
    Plain-English explanation of each button and the overall workflow.

It calls the analysis code IN-PROCESS, so it also works after being compiled to a
single .exe with PyInstaller (see build_exe.bat). Standard-library GUI (tkinter);
the chart features need  pip install pandas matplotlib. The forensics buttons shell
out to PowerShell scripts in a new console window (they need their own venvs and may
prompt on-device).
"""

import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, ttk

# Resolve folders whether running from source or from a PyInstaller bundle.
if getattr(sys, "frozen", False):
    BASE = os.path.dirname(sys.executable)
else:
    BASE = os.path.dirname(os.path.abspath(__file__))
ANALYSIS = os.path.join(BASE, "analysis")
FORENSICS = os.path.join(ANALYSIS, "forensics")
SCAN_RESULTS = os.path.join(BASE, "scan_results")

# Force matplotlib to render to files (Agg) so it never fights the Tk window.
os.environ.setdefault("MPLBACKEND", "Agg")

# Make the analysis modules importable, then import them so PyInstaller bundles
# them (and pandas/matplotlib) into the .exe. If deps are missing we degrade
# gracefully and tell the user when they click.
sys.path.insert(0, ANALYSIS)
try:
    import analyze_scan
except Exception as _e:          # pandas/matplotlib not installed, etc.
    analyze_scan = None
    _scan_err = str(_e)
try:
    import analyze_capture
except Exception as _e:
    analyze_capture = None
    _capture_err = str(_e)


class Launcher(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("HackCheck Companion")
        self.geometry("780x600")

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.run_tab = ttk.Frame(nb)
        self.help_tab = ttk.Frame(nb)
        nb.add(self.run_tab, text="Run")
        nb.add(self.help_tab, text="Help")
        self._build_run_tab()
        self._build_help_tab()

    # ------------------------------------------------------------------ Run --
    def _build_run_tab(self):
        pad = {"padx": 10, "pady": 6}
        ttk.Label(self.run_tab, text="HackCheck PC tools -- click a button, follow the prompt.",
                  font=("Segoe UI", 11, "bold")).pack(anchor="w", **pad)
        ttk.Button(self.run_tab, text="1.  Analyze a scan export  (network + apps + findings)",
                   command=self.analyze_scan_export).pack(fill="x", **pad)
        ttk.Button(self.run_tab, text="2.  Analyze a traffic capture export",
                   command=self.analyze_capture_export).pack(fill="x", **pad)
        ttk.Separator(self.run_tab, orient="horizontal").pack(fill="x", padx=10, pady=4)
        ttk.Button(self.run_tab, text="3.  Set up forensics tools  (MVT + ALEAPP, one-time)",
                   command=self.setup_forensics).pack(fill="x", **pad)
        ttk.Button(self.run_tab, text="4.  Run device forensics  (acquire + scan, push-button)",
                   command=self.run_forensics).pack(fill="x", **pad)
        ttk.Button(self.run_tab, text="Open the results folder",
                   command=self.open_results).pack(fill="x", **pad)
        self.output = tk.Text(self.run_tab, height=15, wrap="word",
                              bg="#111", fg="#eee", insertbackground="#eee")
        self.output.pack(fill="both", expand=True, padx=10, pady=(6, 10))
        self._log("Ready. Outputs are written next to your export, in an *_analysis folder.\n")

    def _log(self, text):
        self.output.insert("end", text)
        self.output.see("end")

    def _run_callable(self, fn, label):
        """Run fn() on a worker thread, streaming its stdout into the log."""
        class _Writer:
            def __init__(self, app): self.app = app
            def write(self, s):
                if s:
                    self.app.after(0, self.app._log, s)
            def flush(self): pass

        def worker():
            self.after(0, self._log, f"\n▶ {label}\n")
            old_out, old_err = sys.stdout, sys.stderr
            sys.stdout = sys.stderr = _Writer(self)
            try:
                fn()
                self.after(0, self._log, "✓ Done.\n")
            except Exception as e:
                self.after(0, self._log, f"✗ {e}\n")
            finally:
                sys.stdout, sys.stderr = old_out, old_err
        threading.Thread(target=worker, daemon=True).start()

    # ----------------------------------------------------------- actions --
    def analyze_scan_export(self):
        if analyze_scan is None:
            self._log(f"\n✗ Scan analysis needs pandas + matplotlib. "
                      f"Run:  pip install pandas matplotlib\n   ({_scan_err})\n")
            return
        folder = filedialog.askdirectory(
            title="Select the folder containing hackcheck_network_*.csv "
                  "(the phone's Download folder, or wherever you copied it)")
        if not folder:
            return
        from pathlib import Path
        self._run_callable(lambda: analyze_scan.analyze(Path(folder)),
                           "Analyzing scan export…")

    def analyze_capture_export(self):
        if analyze_capture is None:
            self._log(f"\n✗ Capture analysis needs pandas + matplotlib. "
                      f"Run:  pip install pandas matplotlib\n   ({_capture_err})\n")
            return
        path = filedialog.askopenfilename(
            title="Select a hackcheck_capture_log_*.csv (or Cancel to pick a folder instead)",
            filetypes=[("CSV files", "*.csv"), ("All files", "*.*")])
        if not path:
            path = filedialog.askdirectory(title="Select the folder containing the capture log CSV")
            if not path:
                return
        from pathlib import Path
        self._run_callable(lambda: analyze_capture.analyze(Path(path)),
                           "Analyzing traffic capture export…")

    def _run_powershell_script(self, script_name, label):
        script = os.path.join(FORENSICS, script_name)
        if not os.path.exists(script):
            self._log(f"\n✗ {script_name} not found in {FORENSICS}\n")
            return
        try:
            flags = subprocess.CREATE_NEW_CONSOLE if os.name == "nt" else 0
            subprocess.Popen(
                ["powershell", "-ExecutionPolicy", "Bypass", "-File", script],
                cwd=FORENSICS, creationflags=flags)
            self._log(f"\n▶ Started {label} in a new PowerShell window. "
                      f"Follow the prompts there; this may take a few minutes.\n")
        except Exception as e:
            self._log(f"\n✗ Could not start {label}: {e}\n")

    def setup_forensics(self):
        self._run_powershell_script("setup.ps1", "forensics setup (MVT + ALEAPP install)")

    def run_forensics(self):
        self._run_powershell_script("run_forensics.ps1", "device forensics (acquire + MVT scan)")

    def open_results(self):
        os.makedirs(SCAN_RESULTS, exist_ok=True)
        if os.name == "nt":
            os.startfile(SCAN_RESULTS)  # noqa: S606 (Windows convenience)
        else:
            subprocess.Popen(["open", SCAN_RESULTS])
        self._log(f"\nOpened: {SCAN_RESULTS}\n"
                  f"(Forensics results land in {FORENSICS} instead, in timestamped folders.)\n")

    # ----------------------------------------------------------------- Help --
    def _build_help_tab(self):
        help_text = (
            "HackCheck Companion -- how to use the buttons\n"
            "==============================================\n\n"
            "One-time setup:\n"
            "  * Chart libraries:  pip install pandas matplotlib\n"
            "  (If you use the compiled .exe, pandas/matplotlib are already inside it.)\n"
            "  * PowerShell buttons (3 and 4) need Python + PowerShell on this PC, plus\n"
            "    ADB debugging enabled on the phone and androidqf.exe in analysis\\forensics\n"
            "    (see analysis\\forensics\\README.md for the one-time manual download step).\n\n"
            "Button 1 -- Analyze a scan export\n"
            "  Use after running a Scan in the HackCheck app and exporting it (Downloads).\n"
            "  Copy hackcheck_network_*.csv, hackcheck_app_inventory_*.csv, and\n"
            "  hackcheck_findings_*.csv (same timestamp) to one folder on this PC, then\n"
            "  click this and select that folder. Writes pivots + a chart into a\n"
            "  scan_analysis subfolder next to the CSVs.\n\n"
            "Button 2 -- Analyze a traffic capture export\n"
            "  Only relevant once the in-app Traffic Capture feature is enabled. Pick the\n"
            "  hackcheck_capture_log_*.csv file. Writes per-app / per-remote pivots and\n"
            "  charts into a capture_analysis subfolder.\n\n"
            "Button 3 -- Set up forensics tools (one-time)\n"
            "  Installs MVT and ALEAPP into isolated Python venvs inside\n"
            "  analysis\\forensics. Only needs to be run once per PC.\n\n"
            "Button 4 -- Run device forensics (push-button)\n"
            "  Connect your phone via USB with debugging enabled, then click this. It runs\n"
            "  AndroidQF to pull a full acquisition from the phone, then MVT to scan it for\n"
            "  known indicators of compromise. Results land in timestamped folders inside\n"
            "  analysis\\forensics. Requires button 3 to have been run first.\n\n"
            "Open the results folder\n"
            "  Jumps to the scan_results folder next to this app. (Forensics results are in\n"
            "  analysis\\forensics instead -- open that folder directly if you need them.)\n\n"
            "Typical workflow:\n"
            "  Run a Scan/Monitor session in the app -> export -> Button 1 -> review findings.\n"
            "  For a deeper check: Button 3 (once) -> Button 4 -> review the MVT report.\n"
        )
        box = tk.Text(self.help_tab, wrap="word", padx=12, pady=12)
        box.insert("1.0", help_text)
        box.config(state="disabled")
        box.pack(fill="both", expand=True)


if __name__ == "__main__":
    Launcher().mainloop()
