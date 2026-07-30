"""
Breaks down a HackCheck traffic-capture export (hackcheck_capture_log_*.csv) into
readable pivots + charts: which apps talked to what, how much data, and any patterns
worth a second look (apps with unusually many distinct destinations, "Unknown"
app flows that couldn't be attributed to a package, etc).

Usage:
    python analyze_capture.py [path_to_dir_or_csv]

If a directory is given (default: ./scan_results next to this script's parent repo,
or the current directory), the newest hackcheck_capture_log_*.csv in it is used.
"""
import sys
from pathlib import Path

import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def find_latest_capture_csv(root: Path) -> Path:
    if root.is_file():
        return root
    candidates = sorted(root.glob("hackcheck_capture_log_*.csv"))
    if not candidates:
        raise SystemExit(f"No hackcheck_capture_log_*.csv found under {root}")
    return candidates[-1]


def fmt_bytes(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def main():
    arg = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("scan_results")
    src = find_latest_capture_csv(arg)
    out = src.parent / "capture_analysis"
    out.mkdir(exist_ok=True)

    df = pd.read_csv(src)
    df["Timestamp"] = pd.to_datetime(df["Timestamp"], errors="coerce")
    df["TotalBytes"] = df["BytesSent"].astype("int64") + df["BytesReceived"].astype("int64")

    print(f"Loaded {len(df)} flows from {src.name}")
    print(f"Time range: {df['Timestamp'].min()} .. {df['Timestamp'].max()}")

    # ---------- Per-app pivot ----------
    per_app = df.groupby("App").agg(
        flows=("App", "size"),
        total_bytes=("TotalBytes", "sum"),
        bytes_sent=("BytesSent", "sum"),
        bytes_received=("BytesReceived", "sum"),
        distinct_remotes=("RemoteAddress", "nunique"),
    ).sort_values("total_bytes", ascending=False)
    per_app.to_csv(out / "pivot_by_app.csv")

    print("\n=== Top 15 apps by total data ===")
    top_app_print = per_app.head(15).copy()
    top_app_print["total_bytes"] = top_app_print["total_bytes"].apply(fmt_bytes)
    top_app_print["bytes_sent"] = top_app_print["bytes_sent"].apply(fmt_bytes)
    top_app_print["bytes_received"] = top_app_print["bytes_received"].apply(fmt_bytes)
    print(top_app_print.to_string())

    # ---------- Apps talking to unusually many distinct destinations ----------
    fanout = per_app[per_app["flows"] >= 3].sort_values("distinct_remotes", ascending=False)
    print("\n=== Top 10 apps by distinct remote destinations (possible scanning/beaconing pattern) ===")
    print(fanout[["flows", "distinct_remotes", "total_bytes"]].head(10).to_string())

    # ---------- Unknown / unattributed flows ----------
    unknown = df[df["App"].isin(["Unknown", "unknown", ""])]
    print(f"\n=== Unattributed ('Unknown' app) flows: {len(unknown)} of {len(df)} ===")
    if len(unknown):
        unk_by_remote = unknown.groupby("RemoteAddress").agg(
            flows=("RemoteAddress", "size"), total_bytes=("TotalBytes", "sum"),
        ).sort_values("total_bytes", ascending=False)
        unk_by_remote.to_csv(out / "unattributed_flows_by_remote.csv")
        print(unk_by_remote.head(15).to_string())

    # ---------- Per-remote-address pivot ----------
    per_remote = df.groupby("RemoteAddress").agg(
        flows=("RemoteAddress", "size"),
        total_bytes=("TotalBytes", "sum"),
        distinct_apps=("App", "nunique"),
    ).sort_values("total_bytes", ascending=False)
    per_remote.to_csv(out / "pivot_by_remote.csv")
    print("\n=== Top 15 remote destinations by total data ===")
    print(per_remote.head(15).to_string())

    # ---------- Protocol split ----------
    proto = df.groupby("Protocol").agg(flows=("Protocol", "size"), total_bytes=("TotalBytes", "sum"))
    print("\n=== Protocol split ===")
    print(proto.to_string())

    # ---------- Charts ----------
    top10 = per_app.head(10).iloc[::-1]
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.barh(top10.index, top10["bytes_sent"] / 1e6, label="Sent", color="#FF6A3D")
    ax.barh(top10.index, top10["bytes_received"] / 1e6, left=top10["bytes_sent"] / 1e6, label="Received", color="#1FBFA6")
    ax.set_xlabel("MB")
    ax.set_title("Top 10 apps by data volume")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "chart_top_apps.png", dpi=150)
    plt.close(fig)

    by_hour = df.dropna(subset=["Timestamp"]).groupby(df["Timestamp"].dt.floor("h")).size()
    fig, ax = plt.subplots(figsize=(12, 5))
    ax.bar(by_hour.index.astype(str), by_hour.values, color="#14202B")
    ax.set_title("Flows started per hour")
    ax.set_ylabel("Flows")
    ax.tick_params(axis="x", rotation=45)
    fig.tight_layout()
    fig.savefig(out / "chart_flows_by_hour.png", dpi=150)
    plt.close(fig)

    dist = per_app.head(10).iloc[::-1]
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.barh(dist.index, dist["distinct_remotes"], color="#FF6A3D")
    ax.set_xlabel("Distinct remote addresses")
    ax.set_title("Top 10 apps by distinct destinations")
    fig.tight_layout()
    fig.savefig(out / "chart_distinct_destinations.png", dpi=150)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(6, 6))
    ax.pie(proto["total_bytes"], labels=proto.index, autopct="%1.0f%%", colors=["#1FBFA6", "#FF6A3D", "#14202B"])
    ax.set_title("Data volume by protocol")
    fig.tight_layout()
    fig.savefig(out / "chart_protocol_split.png", dpi=150)
    plt.close(fig)

    print(f"\nAll outputs saved to: {out}")


if __name__ == "__main__":
    main()
