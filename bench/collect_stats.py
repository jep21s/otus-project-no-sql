"""Сбор ресурсов контейнеров кейса в CSV (sampling 1 Гц).
Запускается на хосте: python collect_stats.py --case a1 --out results/a1/run-1/stats --duration 120
"""
import argparse
import csv
import os
import time
from datetime import datetime

import docker


def calc_cpu_percent(stats):
    cpu = stats["cpu_stats"]
    pre = stats["precpu_stats"]
    cpu_delta = cpu["cpu_usage"]["total_usage"] - pre["cpu_usage"]["total_usage"]
    sys_delta = cpu["system_cpu_usage"] - pre["system_cpu_usage"]
    online = cpu.get("online_cpus") or len(cpu["cpu_usage"].get("percpu_usage") or []) or 1
    if sys_delta > 0 and cpu_delta >= 0:
        return (cpu_delta / sys_delta) * online * 100.0
    return 0.0


def net_io(stats):
    rx = tx = 0
    for _, v in (stats.get("networks") or {}).items():
        rx += v.get("rx_bytes", 0)
        tx += v.get("tx_bytes", 0)
    return rx, tx


def blk_io(stats):
    io = stats.get("blkio_stats") or {}
    read = sum(b.get("value", 0) for b in (io.get("io_service_bytes_recursive") or []) if b.get("op") == "Read")
    write = sum(b.get("value", 0) for b in (io.get("io_service_bytes_recursive") or []) if b.get("op") == "Write")
    return read, write


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--duration", type=int, default=120)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    client = docker.from_env()
    project = f"orders-{args.case}"

    files = {}
    writers = {}

    def get_containers():
        return client.containers.list(all=False, filters={"label": f"com.docker.compose.project={project}"})

    end = time.time() + args.duration
    while time.time() < end:
        try:
            containers = get_containers()
        except Exception:
            time.sleep(args.interval)
            continue
        ts = datetime.utcnow().isoformat(timespec="seconds")
        for c in containers:
            name = c.name
            # нормализуем имя (убираем префикс проекта)
            short = name.replace(f"orders-{args.case}-", "").replace(f"orders-{args.case}", "").strip("-")
            try:
                s = c.stats(stream=False)
            except Exception:
                continue
            cpu = calc_cpu_percent(s)
            mem = s.get("memory_stats") or {}
            mem_usage = mem.get("usage", 0)
            mem_limit = mem.get("limit", 0)
            mem_pct = (mem_usage / mem_limit * 100.0) if mem_limit else 0.0
            rx, tx = net_io(s)
            br, bw = blk_io(s)
            if short not in writers:
                path = os.path.join(args.out, f"{short}.csv")
                fh = open(path, "w", newline="")
                w = csv.writer(fh)
                w.writerow(["timestamp", "cpu_percent", "mem_usage_bytes", "mem_limit_bytes",
                            "mem_percent", "net_rx_bytes", "net_tx_bytes", "blk_read_bytes", "blk_write_bytes"])
                files[short] = fh
                writers[short] = w
            writers[short].writerow([ts, f"{cpu:.2f}", mem_usage, mem_limit, f"{mem_pct:.2f}", rx, tx, br, bw])
        for fh in files.values():
            fh.flush()
        time.sleep(args.interval)

    for fh in files.values():
        fh.close()
    print(f"[stats] written {len(files)} container CSVs to {args.out}")


if __name__ == "__main__":
    main()
