"""Финальные артефакты исследования.
Генерирует:
  report/RESEARCH_REPORT.md — сводный отчёт по всем прогнанным кейсам (сравнение по тирам).
  RESULT.md — инструкция для модели-генератора презентации PPTX (русский).
Читает все доступные results/<case>/summary/summary.json.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

HYPOTHESES = [
    ("H1", "Couchbase (memory-first) даёт меньшую латентность чтения «из коробки», чем PG+Redis."),
    ("H2", "PG+Redis сложнее в эксплуатации, но предсказуемее по латентности записи под нагрузкой."),
    ("H3", "Couchbase-кластер линейнее растёт по throughput при масштабировании, но жрёт больше RAM."),
    ("H4", "При отказе узла Couchbase восстанавливается быстрее, чем связка PG+Redis."),
    ("H5", "Для pet-projects (Тир A) Couchbase выгоднее по совокупному потреблению ресурсов."),
]

CONTEXT = """\
Микросервис заказов на Kotlin/Spring Boot с послойной архитектурой:
Controller(DTO) → konvert → Service(Model) → Persister[interface] → Repository(Entity).
Две реализации Persister: PG+Redis (Spring Data JPA + Redis, внешний кэш 60 c) и
Couchbase (Spring Data Couchbase, встроенный memory-first кэш + явный кэш 60 c).
REST: CRUD заказов/пользователей/товаров, заголовок X-User-Id.
Топологии: A (single), B (HA БД), C (HA БД + 2 app за nginx).
Протокол прогона: 3 прогона × 2 мин, перед каждым — перезапуск контейнеров, очистка и
пересидирование детерминированного датасета (1000 users, 200 products, ~10000 orders).
Нагрузка: Locust, ~1000 (базовый) / 3000 (стресс) пользователей, профили mixed/read-heavy/write-heavy.
Мониторинг: docker stats + cgroup (CPU/RAM/IO), sampling 1 Гц.
"""


def load_all():
    out = {}
    for d in sorted((ROOT / "results").glob("*")):
        if not d.is_dir():
            continue
        sj = d / "summary" / "summary.json"
        if sj.exists():
            out[d.name] = json.loads(sj.read_text())
    return out


def m(v):
    try:
        return f"{float(v):.2f}"
    except (ValueError, TypeError):
        return "—"


def case_metrics(summary):
    mt = summary.get("metrics") or {}
    containers = summary.get("containers") or {}
    rps = (mt.get("requests_per_s") or {}).get("median")
    p50 = (mt.get("p50") or {}).get("median")
    p90 = (mt.get("p90") or {}).get("median")
    p95 = (mt.get("p95") or {}).get("median")
    p99 = (mt.get("p99") or {}).get("median")
    fail = (mt.get("failures_per_s") or {}).get("median")
    total_cpu = sum((c.get("cpu_avg") or 0) for c in containers.values())
    total_ram = sum((c.get("mem_avg_mb") or 0) for c in containers.values())
    return {
        "rps": rps, "p50": p50, "p90": p90, "p95": p95, "p99": p99, "fail": fail,
        "cpu_sum": total_cpu, "ram_sum_mb": total_ram,
        "n_containers": len(containers),
    }


def write_research_report(data):
    lines = []
    p = lines.append
    p("# RESEARCH REPORT — Couchbase vs PostgreSQL+Redis\n")
    p(CONTEXT)
    p("## Сводная таблица по кейсам\n")
    p("| Кейс | Стек | RPS | p50, мс | p95, мс | p99, мс | Failures/s | Σ CPU % | Σ RAM, МБ |")
    p("|---|---|---|---|---|---|---|---|---|")
    for name, s in data.items():
        cm = case_metrics(s)
        p(f"| {name.upper()} | {s.get('stack')} | {m(cm['rps'])} | {m(cm['p50'])} | "
          f"{m(cm['p95'])} | {m(cm['p99'])} | {m(cm['fail'])} | {m(cm['cpu_sum'])} | {m(cm['ram_sum_mb'])} |")

    p("\n## Сравнение по тирам (PG+Redis vs Couchbase)\n")
    for tier in ["A", "B", "C"]:
        pg = [s for n, s in data.items() if s.get("tier") == tier and s.get("target") == "pg"]
        cb = [s for n, s in data.items() if s.get("tier") == tier and s.get("target") == "couchbase"]
        if not pg and not cb:
            continue
        p(f"### Тир {tier}")
        pgm = case_metrics(pg[0]) if pg else None
        cbm = case_metrics(cb[0]) if cb else None
        p("| Метрика | PG+Redis | Couchbase |")
        p("|---|---|---|")
        for key, label in [("rps", "RPS"), ("p50", "p50, мс"), ("p95", "p95, мс"), ("p99", "p99, мс"),
                           ("fail", "Failures/s"), ("cpu_sum", "Σ CPU %"), ("ram_sum_mb", "Σ RAM, МБ")]:
            p(f"| {label} | {m(pgm[key]) if pgm else '—'} | {m(cbm[key]) if cbm else '—'} |")
        p("")

    p("## Вердикты по гипотезам\n")
    for hid, htext in HYPOTHESES:
        p(f"- **{hid}.** {htext} — _(заполняется по итогам замеров; см. таблицы выше)_")
    p("\n_Отчёт сгенерирован автоматически из results/*/summary/summary.json. "
      "Доработайте вердикты и выводы вручную._\n")

    out = ROOT / "report" / "RESEARCH_REPORT.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines))
    print(f"[write_result] {out}")


def write_result_md(data):
    lines = []
    p = lines.append
    p("# RESULT.md — инструкция для генерации презентации (PPTX)\n")
    p("> Документ подготовлен для модели-генератора презентаций. Все цифры — из замеров; "
      "тезисы к слайдам помечены как заготовки под финальную правку.\n")

    p("## 1. Контекст исследования\n")
    p(CONTEXT)
    p("**Гипотезы:**")
    for hid, htext in HYPOTHESES:
        p(f"- **{hid}:** {htext}")

    p("\n## 2. Методология\n")
    p("- Топологии: A1/A2 (single), B1/B2 (HA БД), C1/C2 (HA БД + 2 app за nginx).")
    p("- Протокол: 3 прогона × 2 мин steady-state; перед каждым прогоном — `down -v` + `up`, "
      "очистка БД и пересидирование детерминированного датасета; cool-down 30 с между прогонами.")
    p("- Нагрузка: Locust, 1000 пользователей (базовый), профили mixed/read-heavy/write-heavy.")
    p("- Метрики: throughput (RPS), latency p50/p90/p95/p99, % ошибок, CPU/RAM/IO контейнеров (docker stats).")

    p("\n## 3. Результаты по кейсам\n")
    for name, s in data.items():
        cm = case_metrics(s)
        p(f"### {name.upper()} — {s.get('stack')}")
        p(f"- RPS: **{m(cm['rps'])}**, p50: **{m(cm['p50'])} мс**, p95: **{m(cm['p95'])} мс**, "
          f"p99: **{m(cm['p99'])} мс**, Failures/s: {m(cm['fail'])}")
        p(f"- Ресурсы: Σ CPU ≈ {m(cm['cpu_sum'])}%, Σ RAM ≈ {m(cm['ram_sum_mb'])} МБ "
          f"({cm['n_containers']} контейнеров).\n")

    p("## 4. План слайдов презентации\n")
    p("Формат описания: **Слайд N: `<заголовок>`** — ключевой тезис — данные/график — буллеты.")
    slides = [
        ("Тема, цель и стек исследования",
         "Сравниваем PG+Redis и Couchbase для микросервиса заказов",
         "Схема слоёв приложения; logo/названия технологий",
         ["Цель исследования", "Стек: Kotlin/Spring Boot, konvert, JPA/Redis/Couchbase", "Гипотезы H1–H5"]),
        ("Гипотезы и методика",
         "5 гипотез + воспроизводимый протокол",
         "Схема тиров A/B/C",
         ["H1–H5 одной строкой", "Протокол: 3×2 мин, restart+seed, 1000 юзеров", "Что замеряем"]),
        ("Архитектура стенда",
         "Две Persister-реализации за одним интерфейсом",
         "Диаграмма: Controller→Service→Persister→Repo",
         ["PG+Redis vs Couchbase", "Кэш 60 c на Persister", "nginx для Tier C"]),
    ]
    for tier in ["A", "B", "C"]:
        slides.append((f"Тир {tier}: производительность",
                       "PG+Redis vs Couchbase — throughput и latency",
                       "Bar-chart RPS + p95 по кейсам тира",
                       ["RPS обоих кейсов", "p95/p99 latency", "кто быстрее и почему"]))
    slides += [
        ("Resource-efficiency (H5, pet-projects)",
         "Какой стек дешевле на малой нагрузке (Тир A)",
         "Σ RAM/Σ CPU по A1 vs A2",
         ["Суммарное потребление ресурсов", "Простота эксплуатации (1 система vs 2)", "Вердикт H5"]),
        ("Масштабируемость (H3)",
         "Линейность роста throughput от A→C",
         "График RPS по тирам для обеих СУБД",
         ["Couchbase кластер vs PG+Redis", "RAM на узел", "Вердикт H3"]),
        ("Отказоустойчивость (H4)",
         "Поведение при отказе узла (chaos)",
         "Timeline: всплеск ошибок и RTO",
         ["kill master/replica", "RTO PG+Redis vs Couchbase", "Потеря запросов"]),
        ("Итоговая сводная таблица",
         "Все кейсы в одной таблице",
         "Таблица из RESEARCH_REPORT.md",
         ["RPS/p95/ресурсы по 6 кейсам", "Подсветить лучших в тире"]),
        ("Выводы и рекомендации",
         "Вердикты по H1–H5 + когда что выбрать",
         "—",
         ["Когда брать Couchbase", "Когда PG+Redis", "Рекомендации для pet-projects"]),
    ]
    for i, (title, thesis, data_note, bullets) in enumerate(slides, 1):
        p(f"\n**Слайд {i}: `{title}`**")
        p(f"- Ключевой тезис: {thesis}.")
        p(f"- Данные/график: {data_note}.")
        p("- Буллеты:")
        for b in bullets:
            p(f"  - {b}")

    p("\n## 5. Приложения\n")
    p("- Сырые метрики и графики: `results/<case>/run-N/` (locust CSV, stats CSV) и `results/<case>/summary/`.")
    p("- Сводный отчёт: `report/RESEARCH_REPORT.md`.")
    p("- План исследования: `PLAN.md`.\n")

    out = ROOT / "RESULT.md"
    out.write_text("\n".join(lines))
    print(f"[write_result] {out}")


def main():
    data = load_all()
    if not data:
        print("[write_result] нет результатов (results/<case>/summary/) — генерирую каркас RESULT.md")
    write_research_report(data)
    write_result_md(data)


if __name__ == "__main__":
    main()
