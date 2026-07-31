"""
Breaks down a HackCheck scan export (network + app inventory + findings CSVs) into
readable pivots + charts: data usage by app, paired Bluetooth devices, hidden apps
with network activity, and a findings summary.

Usage:
    python analyze_scan.py [path_to_dir]

Looks for the newest matching hackcheck_network_*.csv / hackcheck_app_inventory_*.csv /
hackcheck_findings_*.csv in the given directory (default: ./scan_results). All three
must share the same timestamp suffix (i.e. come from the same export).
"""
import re
import sys
from pathlib import Path

import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def find_latest_export(root: Path):
    candidates = sorted(root.glob("hackcheck_network_*.csv"))
    if not candidates:
        raise SystemExit(f"No hackcheck_network_*.csv found under {root}")
    net_path = candidates[-1]
    m = re.match(r"hackcheck_network_(.+)\.csv$", net_path.name)
    ts = m.group(1)
    inv_path = root / f"hackcheck_app_inventory_{ts}.csv"
    findings_path = root / f"hackcheck_findings_{ts}.csv"
    if not inv_path.exists():
        raise SystemExit(f"Missing {inv_path.name} (expected alongside {net_path.name})")
    if not findings_path.exists():
        raise SystemExit(f"Missing {findings_path.name} (expected alongside {net_path.name})")
    return net_path, inv_path, findings_path, ts


def fmt_bytes(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def analyze(root: Path):
    net_path, inv_path, findings_path, ts = find_latest_export(root)
    out = root / "scan_analysis"
    out.mkdir(exist_ok=True)

    net = pd.read_csv(net_path)
    inv = pd.read_csv(inv_path)
    findings = pd.read_csv(findings_path)
    label_map = dict(zip(inv["PackageName"], inv["Label"]))

    print(f"Loaded export {ts}")

    print("\n=== Cell / WiFi snapshot ===")
    for _, row in net[net["Section"].isin(["cell", "wifi"])].iterrows():
        print(f"{row['Section']}.{row['Key']}: {row['Value']}")

    print("\n=== Paired Bluetooth devices ===")
    bt = net[net["Section"] == "bluetooth_paired"]
    for _, row in bt.iterrows():
        print(f"{row['Value']}  ({row['Key']})")

    dup_names = bt["Value"].value_counts()
    dup_names = dup_names[dup_names > 1]
    if len(dup_names):
        print("\nNOTE: repeated device names with different MACs -- verify these are actually distinct/expected devices:")
        print(dup_names.to_string())

    # ---------- Data usage ----------
    usage = net[net["Section"] == "data_usage_7d"].copy()
    usage["package"] = usage["Key"]
    parsed = usage["Value"].str.extract(r"wifi=(\d+);mobile=(\d+)")
    usage["wifi_bytes"] = parsed[0].astype("int64")
    usage["mobile_bytes"] = parsed[1].astype("int64")
    usage["total_bytes"] = usage["wifi_bytes"] + usage["mobile_bytes"]
    usage["label"] = usage["package"].map(label_map).fillna(usage["package"])
    usage = usage.sort_values("total_bytes", ascending=False)
    usage[["label", "package", "wifi_bytes", "mobile_bytes", "total_bytes"]].to_csv(
        out / "data_usage_7d.csv", index=False
    )

    print("\n=== Top 15 apps by data usage (7-day total) ===")
    top = usage.head(15).copy()
    top["total_h"] = top["total_bytes"].apply(fmt_bytes)
    top["wifi_h"] = top["wifi_bytes"].apply(fmt_bytes)
    top["mobile_h"] = top["mobile_bytes"].apply(fmt_bytes)
    print(top[["label", "total_h", "wifi_h", "mobile_h"]].to_string(index=False))

    # Cross-reference: hidden/no-launcher-icon non-system apps with notable data usage
    hidden_pkgs = set(inv[(~inv["IsSystem"]) & (~inv["HasLauncherIcon"])]["PackageName"])
    usage["is_hidden_app"] = usage["package"].isin(hidden_pkgs)
    hidden_usage = usage[usage["is_hidden_app"] & (usage["total_bytes"] > 0)]
    print(f"\n=== Hidden apps with nonzero data usage: {len(hidden_usage)} ===")
    if len(hidden_usage):
        hidden_usage[["label", "package", "total_bytes"]].to_csv(out / "hidden_apps_with_usage.csv", index=False)
        print(hidden_usage[["label", "package", "total_bytes"]].to_string(index=False))

    # ---------- Findings ----------
    findings_by_level = findings.groupby("Level").size().reindex(["HIGH", "MEDIUM", "INFO"], fill_value=0)
    print("\n=== Findings by level (this run) ===")
    print(findings_by_level.to_string())

    # ---------- Charts ----------
    top10 = usage.head(10).iloc[::-1]
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.barh(top10["label"], top10["wifi_bytes"] / 1e6, label="WiFi", color="#1FBFA6")
    ax.barh(top10["label"], top10["mobile_bytes"] / 1e6, left=top10["wifi_bytes"] / 1e6, label="Mobile", color="#FF6A3D")
    ax.set_xlabel("MB (7-day total)")
    ax.set_title("Top 10 apps by data usage, last 7 days")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "chart_top_data_usage.png", dpi=150)
    plt.close(fig)

    print(f"\nAll outputs saved to: {out}")


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("scan_results")
    analyze(root)


if __name__ == "__main__":
    main()
