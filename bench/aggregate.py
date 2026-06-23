"""Агрегация результатов 3 прогонов кейса в results/<case>/summary/.
Читает locust_stats.csv и stats/*.csv, пишет summary.json + summary.csv.
"""
import argparse
import csv
import json
import statistics
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent


def agg(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return None
    out = {"min": min(vals), "max": max(vals), "mean": statistics.mean(vals), "median": statistics.median(vals)}
    if len(vals) > 1:
        out["stddev"] = statistics.stdev(vals)
    return out


def read_locust(run_dir):
    f = run_dir / "locust_stats.csv"
    if not f.exists():
        return None
    with open(f) as fh:
        rows = list(csv.DictReader(fh))
    agg_row = next((r for r in rows if r["Name"] == "Aggregated"), None)
    if not agg_row:
        return None

    def f2(v):
        try:
            return float(v)
        except (ValueError, TypeError):
            return None

    return {
        "requests_per_s": f2(agg_row.get("Requests/s")),
        "total_requests": f2(agg_row.get("Request Count")),
        "failures_per_s": f2(agg_row.get("Failures/s")),
        "p50": f2(agg_row.get("50%")),
        "p90": f2(agg_row.get("90%")),
        "p95": f2(agg_row.get("95%")),
        "p99": f2(agg_row.get("99%")),
        "avg": f2(agg_row.get("Average Response Time")),
    }


def read_stats(run_dir):
    stats_dir = run_dir / "stats"
    if not stats_dir.exists():
        return {}
    out = {}
    for f in sorted(stats_dir.glob("*.csv")):
        name = f.stem
        # исключаем инфраструктурные контейнеры (locust/seed/nginx-клиент)
        if any(x in name for x in ("bench-run", "locust", "seed-run")):
            continue
        cpu_vals, mem_vals = [], []
        net_rx_last = net_tx_last = 0
        with open(f) as fh:
            for row in csv.DictReader(fh):
                try:
                    cpu_vals.append(float(row["cpu_percent"]))
                    mem_vals.append(float(row["mem_usage_bytes"]))
                    net_rx_last = int(row["net_rx_bytes"])
                    net_tx_last = int(row["net_tx_bytes"])
                except (ValueError, KeyError):
                    continue
        if not cpu_vals:
            continue
        out[name] = {
            "cpu_avg": statistics.mean(cpu_vals),
            "cpu_peak": max(cpu_vals),
            "mem_avg_mb": statistics.mean(mem_vals) / 1024 / 1024,
            "mem_peak_mb": max(mem_vals) / 1024 / 1024,
            "net_rx_total_mb": net_rx_last / 1024 / 1024,
            "net_tx_total_mb": net_tx_last / 1024 / 1024,
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", required=True)
    args = ap.parse_args()

    with open(ROOT / "bench" / "cases.yaml") as fh:
        case = yaml.safe_load(fh)["cases"][args.case]
    results_root = ROOT / "results" / args.case
    summary_dir = results_root / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)

    runs_data = []
    container_runs = {}
    for run_dir in sorted(results_root.glob("run-*")):
        lc = read_locust(run_dir)
        if lc:
            runs_data.append({"run": run_dir.name, **lc})
        for cname, cvals in read_stats(run_dir).items():
            container_runs.setdefault(cname, []).append(cvals)

    # агрегаты по метрикам
    metrics = {}
    if runs_data:
        for key in ["requests_per_s", "p50", "p90", "p95", "p99", "avg", "total_requests", "failures_per_s"]:
            metrics[key] = agg([r.get(key) for r in runs_data])

    containers = {}
    for cname, lst in container_runs.items():
        containers[cname] = {
            "cpu_avg": agg([x["cpu_avg"] for x in lst])["median"],
            "cpu_peak": agg([x["cpu_peak"] for x in lst])["max"],
            "mem_avg_mb": agg([x["mem_avg_mb"] for x in lst])["median"],
            "mem_peak_mb": agg([x["mem_peak_mb"] for x in lst])["max"],
            "runs": lst,
        }

    summary = {
        "case": args.case,
        "stack": case["stack"],
        "tier": case["tier"],
        "target": case["target"],
        "runs": runs_data,
        "metrics": metrics,
        "containers": containers,
    }
    with open(summary_dir / "summary.json", "w") as fh:
        json.dump(summary, fh, indent=2, default=str)
    print(f"[aggregate] {args.case}: {len(runs_data)} runs -> {summary_dir/'summary.json'}")


if __name__ == "__main__":
    main()
