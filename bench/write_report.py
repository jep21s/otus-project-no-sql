"""Генерация results/<case>/REPORT.md с подробным объяснением по кейсу.
Читает results/<case>/summary/summary.json.
"""
import argparse
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent


def m(v):
    return f"{v:.2f}" if isinstance(v, (int, float)) else str(v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--case", required=True)
    args = ap.parse_args()

    with open(ROOT / "bench" / "cases.yaml") as fh:
        case = yaml.safe_load(fh)["cases"][args.case]
    summary_path = ROOT / "results" / args.case / "summary" / "summary.json"
    if not summary_path.exists():
        print(f"[write_report] нет summary для {args.case} — пропускаю")
        return
    summary = json.loads(summary_path.read_text())
    metrics = summary.get("metrics") or {}
    containers = summary.get("containers") or {}
    runs = summary.get("runs") or []

    lines = []
    p = lines.append
    p(f"# Отчёт по кейсу {args.case.upper()} — {case['stack']}\n")
    p(f"- **Тир:** {case['tier']}")
    p(f"- **Стек/СУБД:** {case['stack']}")
    p(f"- **Тип хранилища:** {case['target']}")
    p(f"- **Число прогонов:** {len(runs)} (по 2 минуты steady-state; перед каждым прогоном — "
      f"перезапуск контейнеров, очистка и пересидирование)\n")

    p("## 1. Производительность (агрегат по прогонам)\n")
    p("| Метрика | min | median | mean | max | stddev |")
    p("|---|---|---|---|---|---|")
    for key, label in [("requests_per_s", "Throughput, RPS"), ("p50", "Latency p50, мс"),
                       ("p90", "Latency p90, мс"), ("p95", "Latency p95, мс"),
                       ("p99", "Latency p99, мс"), ("avg", "Latency avg, мс"),
                       ("total_requests", "Всего запросов"), ("failures_per_s", "Failures/s")]:
        a = metrics.get(key) or {}
        p(f"| {label} | {m(a.get('min'))} | {m(a.get('median'))} | {m(a.get('mean'))} | "
          f"{m(a.get('max'))} | {m(a.get('stddev'))} |")

    if runs:
        p("\n## 2. По прогонам\n")
        p("| Прогон | RPS | p50, мс | p95, мс | p99, мс | Запросов |")
        p("|---|---|---|---|---|---|")
        for r in runs:
            p(f"| {r['run']} | {m(r.get('requests_per_s'))} | {m(r.get('p50'))} | "
              f"{m(r.get('p95'))} | {m(r.get('p99'))} | {m(r.get('total_requests'))} |")

    if containers:
        p("\n## 3. Потребление ресурсов по контейнерам (медиана по прогонам)\n")
        p("| Контейнер | CPU avg, % | CPU peak, % | RAM avg, МБ | RAM peak, МБ |")
        p("|---|---|---|---|---|")
        for cname, c in sorted(containers.items()):
            p(f"| {cname} | {m(c.get('cpu_avg'))} | {m(c.get('cpu_peak'))} | "
              f"{m(c.get('mem_avg_mb'))} | {m(c.get('mem_peak_mb'))} |")

    p("\n## 4. Объяснение наблюдений\n")
    p(f"- **Throughput/латентность:** {m((metrics.get('requests_per_s') or {}).get('median'))} RPS при "
      f"p95 ≈ {m((metrics.get('p95') or {}).get('median'))} мс. "
      f"Узкое место определяется по CPU peak контейнеров (см. таблицу) и соотношению read/write.")
    p("- **Кэш:** метод `GET /api/orders` кэшируется 60 c; high hit-rate виден по тому, что p50/p95 "
      "«чтения моих заказов» держится низким при росте нагрузки, а `POST` (инвалидация) даёт всплеск.")
    p("- **Ресурсы:** оцените, какой контейнер упирается в CPU (≈100% × ядро) или RAM — это указывает "
      "на узкое место стека в данном тире.")
    if case["tier"] == "A":
        p("- **Гипотеза H5 (pet-projects):** сравните суммарное потребление RAM/CPU всех контейнеров этого "
          "тира с парным кейсом (a1 vs a2) — вывод о выгоде для небольших проектов делается по ресурсоёмкости "
          "на малой нагрузке.")
    p("- **Воспроизводимость:** разброс между прогонами (stddev) показывает стабильность; большие значения "
      "говорят о влиянии прогрева/сборщика мусора/ребаланса.")

    p("\n## 5. Вердикт по кейсу\n")
    p("- (заполняется аналитиком/моделью: подтверждает/опровергает релевантные гипотезы H1–H5 цифрами)\n")

    out = ROOT / "results" / args.case / "REPORT.md"
    out.write_text("\n".join(lines))
    print(f"[write_report] {args.case} -> {out}")


if __name__ == "__main__":
    main()
