# HackCheck analysis scripts

PC-side companion scripts for interpreting exported CSVs. Requires Python with
`pandas` and `matplotlib` (`pip install pandas matplotlib`).

## analyze_capture.py

Breaks down a `hackcheck_capture_log_*.csv` export (from the Traffic Capture
screen) into readable pivots and charts:

- Top apps by data volume (sent/received split)
- Apps with unusually many distinct remote destinations (possible
  scanning/beaconing pattern worth a second look)
- **Unattributed ("Unknown" app) flows** — connections we couldn't map back
  to a package; worth checking closely, since a hidden/uninstalled-but-still-
  running process is exactly what an "Unknown" attribution can indicate
- Top remote destinations by data volume, and how many distinct apps talk to
  each one
- Protocol split (TCP vs UDP) by data volume
- Flows-per-hour timeline

Usage, from the repo root, after pulling an export into `scan_results/` (e.g.
via `adb pull`):

```
python analysis/analyze_capture.py scan_results
```

Outputs land in `scan_results/capture_analysis/` (pivot CSVs + PNG charts).
That whole `scan_results/` tree is gitignored — never commit real capture
data, this repo is public.
