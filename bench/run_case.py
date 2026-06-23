"""Оркестратор прогона одного кейса: 3 прогона по 2 мин с полным restart+clean+seed.
Запуск: python bench/run_case.py a1 [--users 1000] [--profile mixed] [--runs 3]
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
BENCH_OVERRIDE = ROOT / "docker-compose" / "bench-run.yml"


def load_case(name):
    with open(ROOT / "bench" / "cases.yaml") as f:
        cfg = yaml.safe_load(f)
    if name not in cfg["cases"]:
        sys.exit(f"unknown case: {name}; known: {list(cfg['cases'])}")
    case = cfg["cases"][name]
    case["name"] = name
    case.setdefault("defaults_merged", cfg["defaults"])
    return case, cfg["defaults"]


def run(cmd, check=True, env=None):
    print("  $", " ".join(str(c) for c in cmd))
    return subprocess.run(cmd, check=check, env={**os.environ, **(env or {})})


def compose(case, *args, env=None, check=True, with_bench=False):
    files = ["-f", str(ROOT / case["compose"])]
    if with_bench:
        files += ["-f", str(BENCH_OVERRIDE)]
    cmd = ["docker", "compose", *files, "-p", f"orders-{case['name']}"]
    return run(cmd + list(args), check=check, env=env)


def bench_env(case):
    env = {"TARGET": case["target"]}
    if case["target"] == "pg":
        env.update(PGHOST=case.get("pg_host", "postgres"), PGPORT="5432",
                   PGDATABASE="orders", PGUSER="orders", PGPASSWORD="orders")
    else:
        env.update(CB_CONN=case["cb_conn"], CB_USER="Administrator",
                   CB_PASS="password", CB_BUCKET="orders")
    return env


def wait_app_healthy(case, timeout=180):
    """Poll HTTP /actuator/health через опубликованный порт 8080.
    Считаем приложение готовым, когда эндпоинт отвечает (200 UP или 503 — Spring уже поднят)."""
    print(f"  [wait] app ready via http (up to {timeout}s)...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = subprocess.run(
                ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}",
                 "-m", "3", "http://localhost:8080/actuator/health"],
                capture_output=True, text=True)
            code = r.stdout.strip()
            if code in ("200", "503"):
                print(f"  [wait] app ready (health={code})")
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("case")
    ap.add_argument("--users", type=int, default=None)
    ap.add_argument("--profile", default="mixed")
    ap.add_argument("--runs", type=int, default=None)
    ap.add_argument("--duration", type=int, default=None)
    ap.add_argument("--smoke", action="store_true", help="быстрый одиночный прогон для валидации")
    args = ap.parse_args()

    case, defaults = load_case(args.case)
    users = args.users or defaults["users"]
    runs = 1 if args.smoke else (args.runs or defaults["runs"])
    duration = 15 if args.smoke else (args.duration or defaults["duration_sec"])
    cooldown = 5 if args.smoke else defaults["cooldown_sec"]

    print(f"\n===== CASE {case['name'].upper()} :: {case['stack']} =====")
    print(f"    target={case['target']} bench_host={case['bench_host']} "
          f"users={users} duration={duration}s runs={runs} profile={args.profile}")

    results_root = ROOT / "results" / case["name"]

    for run_idx in range(1, runs + 1):
        run_dir = results_root / f"run-{run_idx}"
        run_dir.mkdir(parents=True, exist_ok=True)
        print(f"\n----- {case['name']} run-{run_idx}/{runs} -----")

        # для Couchbase multi-node: (пере)генерируем сертификаты общего CA
        if case.get("certs"):
            gen = [str(ROOT / "docker-compose" / "couchbase" / "gen-certs.sh")] + list(case["certs"])
            print("  $", " ".join(gen))
            subprocess.run(gen, check=True)

        # 1. restart контейнеров с нуля
        compose(case, "down", "-v", "--remove-orphans", check=False)
        compose(case, "up", "-d", "--wait")

        # для multi-app кейсов ждём app-1 (или проверяем nginx)
        if not wait_app_healthy(case):
            print("  [warn] app не стал healthy — пробуем продолжить")

        # 3+4. clean + seed через одноразовый запуск приложения (app.seed) — единый путь для PG и Couchbase
        compose(case, "run", "--rm", "--no-deps", "seed",
                env={"SEED_PROFILE": case["name"]}, with_bench=True)

        # 5. cooldown
        print(f"  [cooldown] {cooldown}s")
        time.sleep(cooldown)

        # 6. collect_stats (фон) + locust
        stats_proc = subprocess.Popen(
            [sys.executable, str(ROOT / "bench" / "collect_stats.py"),
             "--case", case["name"], "--out", str(run_dir / "stats"),
             "--duration", str(duration)])
        time.sleep(2)

        locust_env = {"LOCUST_PROFILE": args.profile}
        mount = f"{run_dir}:/out"
        compose(case, "run", "--rm", "--no-deps", "-v", mount,
                "bench", "locust", "-f", "/load/locustfile.py", "--headless",
                "-u", str(users), "-r", str(min(25, users)),
                "-t", f"{duration}s", "--host", f"http://{case['bench_host']}:8080",
                "--csv", "/out/locust", env=locust_env, with_bench=True, check=False)

        stats_proc.wait(timeout=30)

    # отчёт по кейсу
    subprocess.run([sys.executable, str(ROOT / "bench" / "aggregate.py"), "--case", case["name"]], check=False)
    subprocess.run([sys.executable, str(ROOT / "bench" / "write_report.py"), "--case", case["name"]], check=False)

    # финальный разбор стенда, чтобы освободить порты/ресурсы для следующего кейса
    compose(case, "down", "-v", "--remove-orphans", check=False)

    print(f"\n[done] case {case['name']}: results в {results_root}")


if __name__ == "__main__":
    main()
