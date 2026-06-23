"""Chaos-тест: убиваем узел под нагрузкой и измеряем всплеск ошибок и RTO.
python bench/chaos_test.py b1 --victim pg-replica --kill-at 30 --duration 90
"""
import argparse
import csv
import json
import subprocess
import sys
import time
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
BENCH_OVERRIDE = ROOT / "docker-compose" / "bench-run.yml"


def run(cmd, env=None, check=True):
    print("  $", " ".join(str(c) for c in cmd))
    return subprocess.run(cmd, check=check, env={**__import__("os").environ, **(env or {})})


def compose(case, name, *args, env=None, check=True, with_bench=False):
    files = ["-f", str(ROOT / case["compose"])]
    if with_bench:
        files += ["-f", str(BENCH_OVERRIDE)]
    cmd = ["docker", "compose", *files, "-p", f"orders-{name}"]
    return run(cmd + list(args), check=check, env=env)


def bench_env(case):
    env = {"TARGET": case["target"]}
    if case["target"] == "pg":
        env.update(PGHOST=case.get("pg_host", "postgres"), PGPORT="5432",
                   PGDATABASE="orders", PGUSER="orders", PGPASSWORD="orders")
    else:
        env.update(CB_CONN=case["cb_conn"], CB_USER="Administrator", CB_PASS="password", CB_BUCKET="orders")
    return env


def parse_rto(history_csv, kill_epoch, end_epoch):
    """RTO = время от kill до момента, когда failures/s снова ~0."""
    if not history_csv.exists():
        return None
    rows = list(csv.DictReader(open(history_csv)))
    # ищем Aggregated-строки
    last_failure_epoch = kill_epoch
    for r in rows:
        if r.get("Type") != "Aggregated":
            continue
        try:
            ts = int(r["Timestamp"])
            failures = float(r.get("Failures/s") or 0)
        except (ValueError, KeyError):
            continue
        if ts >= kill_epoch and failures > 1.0:
            last_failure_epoch = max(last_failure_epoch, ts)
    return last_failure_epoch - kill_epoch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("case")
    ap.add_argument("--victim", required=True, help="имя сервиса для docker stop (напр. pg-replica, couchbase-node-2)")
    ap.add_argument("--kill-at", type=int, default=30)
    ap.add_argument("--duration", type=int, default=90)
    ap.add_argument("--users", type=int, default=1000)
    args = ap.parse_args()

    with open(ROOT / "bench" / "cases.yaml") as fh:
        case = yaml.safe_load(fh)["cases"][args.case]
    name = args.case

    out_dir = ROOT / "results" / name / "chaos"
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n===== CHAOS {name.upper()} :: kill {args.victim} at t={args.kill_at}s =====")
    compose(case, name, "down", "-v", "--remove-orphans", check=False)
    compose(case, name, "up", "-d", "--wait")
    compose(case, name, "run", "--rm", "--no-deps", "seed", env={"SEED_PROFILE": name}, with_bench=True)
    time.sleep(10)

    # жертва-контейнер: имя = orders-<case>-<victim>-1 (compose naming)
    victim_container = f"orders-{name}-{args.victim}-1"
    print(f"  victim container: {victim_container}")

    stats_proc = subprocess.Popen(
        [sys.executable, str(ROOT / "bench" / "collect_stats.py"),
         "--case", name, "--out", str(out_dir / "stats"), "--duration", str(args.duration)])

    locust_env = bench_env(case)
    locust_env["LOCUST_PROFILE"] = "mixed"
    mount = f"{out_dir}:/out"
    locust_proc = subprocess.Popen(
        ["docker", "compose", "-f", str(ROOT / case["compose"]), "-f", str(BENCH_OVERRIDE),
         "-p", f"orders-{name}", "run", "--rm", "--no-deps", "-v", mount,
         "bench", "locust", "-f", "/load/locustfile.py", "--headless",
         "-u", str(args.users), "-r", "25", "-t", f"{args.duration}s",
         "--host", f"http://{case['bench_host']}:8080", "--csv", "/out/locust", "--reset-stats"],
        env={**__import__("os").environ, **locust_env})

    kill_epoch = int(time.time()) + args.kill_at
    time.sleep(args.kill_at)
    print(f"  [chaos] KILL {victim_container} at {time.strftime('%H:%M:%S')}")
    subprocess.run(["docker", "stop", victim_container], check=False)
    # ждём под нагрузкой ещё, затем поднимаем
    time.sleep(max(5, args.duration - args.kill_at - 30))
    print(f"  [chaos] START {victim_container}")
    subprocess.run(["docker", "start", victim_container], check=False)

    locust_proc.wait()
    stats_proc.wait(timeout=30)

    end_epoch = int(time.time())
    rto = parse_rto(out_dir / "locust_stats_history.csv", kill_epoch, end_epoch)
    summary = {
        "case": name, "victim": args.victim, "kill_at": args.kill_at,
        "duration": args.duration, "rto_seconds": rto,
        "kill_epoch": kill_epoch, "end_epoch": end_epoch,
    }
    (out_dir / "chaos.json").write_text(json.dumps(summary, indent=2))
    print(f"\n[chaos] {name}: victim={args.victim} RTO≈{rto}s -> {out_dir/'chaos.json'}")


if __name__ == "__main__":
    main()
