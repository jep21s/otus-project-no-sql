# PLAN.md — Couchbase vs PostgreSQL+Redis: сравнение пропускной способности, отказоустойчивости и потребления ресурсов для микросервиса заказов

## 1. Цели и гипотезы исследования

**Цель:** сравнить две стратегии хранения и кэширования для микросервиса заказов —
реляционную `PostgreSQL + Redis` (БД + внешний кэш) и документную `Couchbase`
(БД с интегрированным управляемым кэшем) — по пропускной способности,
отказоустойчивости и потреблению ресурсов.

**Проверяемые гипотезы:**

- **H1.** Couchbase (memory-first, единый узел) даёт меньшую задержку чтения «из коробки»,
  чем PG+Redis, за счёт встроенного кэша.
- **H2.** PG+Redis сложнее в эксплуатации (две системы + ручная инвалидация кэша),
  но даёт более предсказуемую латентность записи под нагрузкой.
- **H3.** При горизонтальном масштабировании Couchbase-кластер линейнее растёт по throughput,
  но потребляет больше RAM на узел.
- **H4.** При отказе узла кластер Couchbase быстрее восстанавливает обслуживание,
  чем связка PG+Redis (где failover БД и кэша происходит раздельно).
- **H5 (pet-projects).** Для небольших проектов и pet-projects (Тир A, низкая нагрузка)
  Couchbase как единая система выгоднее по совокупному потреблению ресурсов
  (CPU/RAM/диск) и проще в эксплуатации, чем связка PG+Redis, при сопоставимой
  производительности. Проверяется на Тире A с фокусом на resource-efficiency
  при малой нагрузке (помимо throughput).

## 2. Стек технологий

| Слой | Технология |
|---|---|
| Язык | Kotlin 2.1.x, JDK 17 |
| Фреймворк | Spring Boot 3.3.x (Web, Validation, Actuator) |
| Маппинг DTO↔Model↔Entity | konvert (`tech.psgroup.konvert:konvert-mapper`) + KSP |
| БД SQL | PostgreSQL 16 + Spring Data JPA (JpaRepository) |
| Кэш | Redis 7 (Lettuce + commons-pool2) |
| БД NoSQL | Couchbase Server 7.6 Community + Spring Data Couchbase |
| Миграции PG | **Liquibase** (`db/changelog/`), init-ченджлог из JPA-модели |
| Тесты | JUnit 5, MockK, Spring MockMvc, **Testcontainers** (PG / Redis / Couchbase) |
| Сборка | Gradle Kotlin DSL, `bootJar` (fat-jar) |
| Контейнеры | Docker, docker-compose v2 |
| Нагрузка | **Locust** (Python, headless) |
| Мониторинг ресурсов | docker stats + чтение cgroup (`/sys/fs/cgroup`) → CSV |
| Балансировщик | nginx (для нескольких инстансов приложения) |
| Сидирование | Python-скрипт напрямую в БД (psycopg/asyncpg, Couchbase SDK) |

## 3. Архитектура микросервиса (слои)

Поток данных строго по слоям, Service знает только интерфейс Persister —
ни одного ветвления по профилю в бизнес-логике:

```
HTTP (DTO) ──konvert──▶ Service (Model) ──▶ Persister[interface] (Model)
                                                        │
                         ◀──konvert── Repository(Entity)─┘
                                          │
                          PG: JpaRepository + Redis      Couchbase: CouchbaseRepository
```

Пакеты:

```
com.example.orderservice
├── api
│   ├── controller   OrderController, UserController, ProductController
│   └── dto          *Dto, *Request (с валидацией)
├── model            Order, User, Product, OrderItem, Address (чистый Kotlin, без аннотаций БД)
├── mapper           konvert: OrderDtoMapper, UserDtoMapper, ProductDtoMapper (DTO↔Model)
├── service          OrderService, UserService, ProductService
├── persister
│   ├── api          OrderPersister, UserPersister, ProductPersister (интерфейсы)
│   ├── sql          Sql*Persister + SqlCacheConfig (Redis TTL 60s)
│   └── couchbase    Couchbase*Persister + CouchbaseCacheConfig
├── repository
│   ├── jpa          *JpaRepository
│   └── couchbase    *CouchbaseRepository
├── entity
│   ├── jpa          *Entity, AddressEmbeddable
│   └── couchbase    *Document
└── config           PgRedisConfig (@Profile), CouchbaseConfig (@Profile), WebConfig, CacheProps
```

- Профиль активирует нужный `@Configuration` и бины Persister; альтернативной реализации
  просто нет в контексте — выбирается при сборе приложения.
- Авторизация (исследовательский режим): `WebFilter` читает заголовок `X-User-Id` →
  кладёт `userId` в `RequestContext` (ThreadLocal). Все endpoint'ы считаются
  находящимися в авторизованной зоне, отдельная аутентификация не реализуется.

## 4. Доменная модель и схемы БД

**Сущности:** `User`, `Product`, `Order`, `OrderItem`, `Address` (embedded).

Поля:

- `User`: id(UUID), username, email, firstName, lastName, createdAt, updatedAt
- `Product`: id(UUID), name, description, price(BigDecimal), stock, category, createdAt
- `Order`: id(UUID), userId, status(enum CREATED/PAID/SHIPPED/DELIVERED/CANCELLED),
  totalAmount(BigDecimal), shippingAddress(Address), items(List<OrderItem>), createdAt, updatedAt
- `OrderItem`: id(UUID), productId, productName(snapshot), quantity, unitPrice(snapshot)
- `Address`: street, city, state, zipCode, country

**PostgreSQL (реляционная, нормализация):**

- `users`, `products`, `orders` (FK→users), `order_items` (FK→orders, FK→products);
  поля адреса встроены в `orders`.
- Индексы: `orders(user_id, created_at desc)`, `order_items(order_id)`,
  уникальные `username`, `email`.

**Couchbase (документная, агрегат):**

- Документ `order::{id}` хранит `items` вложенным массивом + адрес встроенным (aggregate root).
- Документы `user::{id}`, `product::{id}`.
- Индексы N1QL: primary, на `type`, `userId` (для «моих заказов»), `status`.

> Различие моделирования (нормализация vs агрегат) — легитимно и отражает сильные
> стороны каждой БД. Persister скрывает это от Service, что само по себе — предмет
> исследования (сложность кода в двух реализациях).

## 5. Кэширование (TTL 60s)

Метод «мои заказы» (`findOrdersByUserId`) кэшируется на слое Persister, TTL = 60 c.

- **PG+Redis:** ключ `orders:user:{userId}`; miss → `SELECT` из PG → write Redis;
  инвалидация при create/update/delete заказа этого пользователя (delete по ключу).
- **Couchbase:** memory-first управляемый кэш встроен; для честного сравнения применяется
  та же явная политика 60s (get/insert с expiry по отдельному ключу). В отчёте также
  фиксируется режим «только встроенный кэш» — это часть сравнения «1 система vs 2».

## 6. REST API (заголовок `X-User-Id`)

| Метод | URL | Операция | Кэш |
|---|---|---|---|
| POST | `/api/orders` | создать заказ | invalidate |
| GET | `/api/orders/{id}` | получить заказ | read (опционально) |
| GET | `/api/orders` | мои заказы (userId из заголовка) | **60s** |
| PATCH | `/api/orders/{id}/status` | сменить статус | invalidate |
| DELETE | `/api/orders/{id}` | удалить заказ | invalidate |
| GET | `/api/users/me` | профиль пользователя | — |
| GET | `/api/products` | каталог товаров | — |
| GET | `/api/products/{id}` | карточка товара | — |

Все CRUD покрыты; стартовая БД не пустая (см. сидирование, §11).

## 7. Топологии тестовых кейсов и оценка потолка ресурсов

Каждый тир — пара PG+Redis vs Couchbase (одинаковый нагрузочный профиль и число
инстансов приложения для корректного сравнения).

| Тир | PG+Redis вариант | Couchbase вариант | App инстансов |
|---|---|---|---|
| **A (baseline)** | 1 PG standalone + 1 Redis standalone | 1 Couchbase (single node, все сервисы) | 1 |
| **B (HA БД)** | PG master + 1 replica + Redis (master + replica + 3 sentinel) | Couchbase 2-node cluster | 1 |
| **C (HA БД + scaled app)** | PG master + 2 replica + Redis Cluster (3 master + 3 replica) | Couchbase 3-node cluster | 2 (за nginx) |

**Итоговый набор кейсов: A1, A2, B1, B2, C1, C2 (6 топологий) + chaos-тесты для B и C.**

**Оценка потолка по ресурсам машины (8 CPU / 30 GB RAM):**

- Couchbase — самый тяжёлый: узел с data+index+query+search ≈ **2–3 GB**;
  3-узловой кластер ≈ 7–9 GB.
- PostgreSQL узел ≈ 0.5–1 GB; Redis ≈ 50–150 MB;
  App (heap ~768m) ≈ 1–1.5 GB контейнер; nginx ≈ мало;
  Locust (3 worker) ≈ 2–3 GB и 2–3 ядра.
- Резерв ОС / Docker / IDE ≈ 4–5 GB.

**Вывод:** реальный потолок — **Тир C** (3-узловой кластер БД + 2–3 инстанса app).
Дальнейшее масштабирование (4+ Couchbase или 4+ app) упрётся в 8 ядер CPU и приведёт
к троттлингу → недостоверным замерам. Поэтому **останавливаемся на Тире C**.

> Nuance: Redis Cluster требует минимум 3 master-узла (кворум) — учтено в Тире C.
> В Тире B для HA кэша используем master + replica + 3 sentinel.

## 8. Инфраструктура: compose-файлы и профили

```
docker-compose/
├── a1-pg-redis-single.yml
├── a2-couchbase-single.yml
├── b1-pg-redis-ha.yml
├── b2-couchbase-2node.yml
├── c1-pg-redis-cluster.yml
├── c2-couchbase-3node.yml
└── nginx.conf (балансировка для нескольких app)

src/main/resources/
├── application.yaml                         (общие настройки)
├── application-a1.yaml ... application-c2.yaml  (datasource/redis/couchbase, пулы, профиль)
└── db/changelog/
    ├── db.changelog-master.xml              (Liquibase, для PG-профилей)
    └── ...changesets

src/main/kotlin/.../config/
├── PgRedisConfig.kt   (@Profile("a1","b1","c1"))   // JPA + Redis beans
└── CouchbaseConfig.kt (@Profile("a2","b2","c2"))   // Couchbase beans

src/main/resources/couchbase/
└── couchbase-init.sh   (создание bucket, scopes/collections, индексов N1QL)
```

- Профиль управляет: datasource, Liquibase, режимом Redis (standalone / sentinel / cluster),
  подключением Couchbase + bucket + индексы.
- `healthchecks` + `depends_on: condition: service_healthy` для детерминированного старта.
- Couchbase init: bucket, scopes/collections, primary/secondary индексы через
  `couchbase-cli` / `cbq`.

## 9. Сборка и контейнеризация

- `build.gradle.kts`: Spring Boot plugin + `bootJar` (fat-jar) + KSP для konvert.
- Мультистейдж `Dockerfile`: `eclipse-temurin:17-jdk` (сборка) →
  `eclipse-temurin:17-jre` (runtime); JVM-флаги под контейнер
  (`-XX:MaxRAMPercentage`, выбор GC).
- **Один образ приложения**; БД-профиль выбирается `SPRING_PROFILES_ACTIVE` в compose.

## 10. Нагрузочное тестирование (Locust) и протокол прогона

### 10.1 Нагрузка

- `load/locustfile.py`: задачи на каждую CRUD-операцию; `userId` берётся из пула
  1000 предзаполненных пользователей (round-robin / случайно); payload реалистичный.
- Headless-запуск:
  `locust --headless -u <users> -r <rate> -t 120s --host ... --csv results/<case>/run-N/locust`.
- **Профили нагрузки:**
  - смешанный (read/write ≈ 80/20 — типичный),
  - read-heavy (95/5 — стресс кэша),
  - write-heavy (30/70 — стресс записи).
- **Уровни:** 1000 (базовый) и 3000 (стресс) одновременных пользователей.
  Spawn-rate ограничивается, чтобы не «положить» сам locust.

### 10.2 Протокол прогона кейса (воспроизводимость и равные условия)

Каждый кейс прогоняется **3 раза подряд** для устойчивости результатов
(учёт прогрева JIT, кэшей ОС, variance), **по 2 минуты steady-state на прогон**
→ минимум **6 минут суммарно** на кейс (плюс накладные на старт/сидирование).

Перед **каждым** из трёх прогонов цикл гарантирует идентичные стартовые условия:

1. `docker compose down -v` + `docker compose -f <case>.yml up -d --wait`
   (перезапуск всех контейнеров кейса «с нуля», чистые тома).
2. health-check всех сервисов (БД, кэш, app).
3. **Очистка БД** (drop/truncate для PG; flush bucket / пересоздание collection для Couchbase).
4. **Сидирование заново** одним и тем же детерминированным датасетом (см. §11).
5. cool-down 30–60 c (стабилизация ресурсов, сброс встроенных кэшей БД) —
   для независимости прогонов между собой.
6. Запуск фонового `collect_stats.py` → старт locust на **120 c** → сбор метрик →
   останов сбора. Результаты каждого прогона пишутся в `results/<case>/run-1|2|3/`.
7. После трёх прогонов — агрегат по ним (median, mean, stddev, min/max) в
   `results/<case>/summary/` и генерация `results/<case>/REPORT.md`.

Порядок кейсов зафиксирован в `cases.yaml`; прогон строго последовательный:
один кейс → полный цикл (3 прогона) → следующий кейс. Между кейсами — полный
`down -v` (никакого «наследия» между топологиями).

> Перезапуск контейнеров и пересидирование перед каждым прогоном устраняют влияние
> «грязного» состояния кэша/буферов предыдущего прогона и делают все 6 кейсов
> сопоставимыми. Это расходует больше времени, но даёт достоверные замеры.

## 11. Сидирование данных (напрямую в БД, перед каждым прогоном)

- `bench/seed.py`: **напрямую в БД**, без участия HTTP-слоя приложения:
  - PG — bulk через `asyncpg`/`psycopg` (`COPY`/`execute_values`),
  - Couchbase — bulk-insert через Couchbase Python SDK.
- Объём: 1000 users, 200 products, ~10 000 orders со items (распределены по пользователям).
- Один детерминированный датасет (фиксированный seed RNG) → воспроизводимость во всех кейсах.
- Запускается **перед каждым из трёх прогонов** кейса, строго после очистки БД
  (см. протокол §10.2) — гарантирует, что все прогоны и все кейсы стартуют на
  абсолютно равных условиях.

## 12. Метрики, сбор ресурсов и детальные результаты

### 12.1 Производительность (Locust)

p50 / p90 / p95 / p99 / mean / min / max latency, RPS, всего запросов, % ошибок —
по каждой операции. Собирается для каждого из 3 прогонов + агрегируется.

### 12.2 Ресурсы (docker stats + cgroup), sampling 1 Гц → CSV

- контейнер: CPU% (от числа ядер), memory RSS/usage, network RX/TX,
  disk read/write (байты/операции);
- внутри app: actuator/micrometer — heap, GC pause (опционально, отдельный scrape).

Скрипт `bench/collect_stats.py` пишет `results/<case>/run-N/stats/<container>.csv`,
запускается фоном на время каждого прогона (120 c).

### 12.3 Отказоустойчивость

Длительность всплеска ошибок, число упавших запросов, time-to-recovery (RTO),
факт потери данных.

### 12.4 Детальные результаты по каждому кейсу (с объяснениями)

Для каждого кейса формируется полноценный отчёт, а не «сухие» CSV:

```
results/<case>/
├── run-1/                    # сырые метрики прогона 1 (locust CSV + stats/<container>.csv)
├── run-2/                    # прогон 2
├── run-3/                    # прогон 3
├── summary/                  # агрегат по 3 прогонам (median/mean/stddev/min/max)
└── REPORT.md                 # нарратив по кейсу с подробным объяснением
```

`results/<case>/REPORT.md` содержит:

- что тестировалось: топология (схема развёртывания), версия/образы БД, профиль
  нагрузки, число пользователей, длительность, число прогонов;
- таблицы latency (p50/p90/p95/p99) и throughput (RPS) по каждой операции —
  для 3 прогонов и агрегата;
- потребление ресурсов (CPU/RAM/сеть/диск) каждого контейнера — пиковое и среднее;
- **подробное объяснение** наблюдений: почему получились такие цифры, где узкое
  место (CPU/IO/кэш), как себя вёл кэш (hit-rate), влияние прогрева (run-1 vs run-3);
- аномалии и выбросы, замеченные особенности (GC-паузы, ребаланс, contention);
- вердикт по релевантным гипотезам для данного кейса (для Тира A — обязательно H5).

Финальный `report/RESEARCH_REPORT.md` — сводка по всем 6 кейсам:
сравнение PG+Redis vs Couchbase в пределах каждого тира, сводные таблицы/графики
и итоговые выводы по гипотезам H1–H5.

## 13. Тесты отказоустойчивости (chaos)

Для **Тиров B и C** (с репликами) — `bench/chaos_test.py <case>`:

1. Старт нагрузки → ожидание steady-state (~30 с).
2. `docker stop` одной реплики посреди нагрузки (отдельный прогон — master/primary).
3. Замер: всплеск ошибок, время до восстановления обслуживания (RTO), потеря запросов.
4. `docker start` узла → замер времени ребаланса / синхронизации.

- PG: убийство master → проверка failover через реплику;
- Redis: sentinel-failover;
- Couchbase: auto-failover (vBucket rebalance).

## 14. Тесты (unit + integration)

**Unit:**

- Service (мок Persister),
- konvert-мапперы (DTO↔Model, Model↔Entity),
- логика инвалидации кэша.

**Integration (Testcontainers):**

- `SqlPersisterIT`: контейнеры PostgreSQL + Redis, реальный путь Model→Entity→DB→Redis.
- `CouchbasePersisterIT`: контейнер Couchbase, N1QL, expiry кэша.
- `ControllerIT`: MockMvc + полный контекст на каждом профиле.
- Проверки: корректность CRUD, попадание/мисс кэша (Redis MONITOR / Couchbase),
  инвалидация, изоляция `userId` из заголовка.

## 15. Python-оркестрация по кейсам

```
bench/
├── run_case.py       # CLI: python run_case.py a1 [--users 1000] [--profile mixed]
├── seed.py           # предзаполнение БД напрямую (перед каждым прогоном)
├── clean_db.py       # очистка PG (truncate) / Couchbase (flush/recreate collection)
├── collect_stats.py  # фоновый сбор docker stats + cgroup
├── chaos_test.py     # инъекция отказов
├── locust_runner.py  # запуск locust headless на 120 c, ожидание, сбор CSV
├── aggregate.py      # агрегат по 3 прогонам + сводная таблица/графики (matplotlib)
├── write_report.py   # генерация results/<case>/REPORT.md с объяснениями
├── write_result.py   # генерация RESULT.md — инструкции для pptx-генератора
└── cases.yaml        # описание всех кейсов: compose, profile, users, duration(120s), runs(3), chaos
```

`run_case.py` выполняет для кейса цикл из **3 прогонов**. Для каждого прогона:

1. `docker compose down -v` → `docker compose -f <case>.yml up -d --wait`
   (перезапуск контейнеров с нуля, чистые тома);
2. health-check сервисов;
3. `clean_db.py` — полная очистка БД;
4. `seed.py` — сидирование детерминированного датасета;
5. cool-down 30–60 c;
6. старт `collect_stats.py` в фоне → `locust_runner.py` на **120 c** → останов сбора;
7. запись прогона в `results/<case>/run-N/`.

После трёх прогонов — `aggregate.py` (агрегат в `summary/`) и `write_report.py`
(генерация `results/<case>/REPORT.md` с подробным объяснением). Затем переход к
следующему кейсу. `aggregate.py` в конце собирает финальный `report/RESEARCH_REPORT.md`.

## 16. Структура репозитория

```
otus-project-no-sql/
├── PLAN.md
├── RESULT.md                  # итоговая инструкция для pptx-генератора (русский)
├── build.gradle.kts, settings.gradle.kts
├── Dockerfile
├── docker-compose/           # 6 compose-файлов + nginx.conf
├── src/main/kotlin/...       # слои приложения (см. §3)
├── src/main/resources/       # application*.yaml, db/changelog (Liquibase), couchbase-init.sh
├── src/test/kotlin/...       # unit + Testcontainers IT
├── load/                     # locustfile.py
├── bench/                    # Python-оркестрация (см. §15)
├── results/                  # метрики по кейсам: <case>/run-1|2|3/ + summary/ + REPORT.md (.gitignore)
└── report/                   # финальный RESEARCH_REPORT.md + сводные таблицы/графики
```

## 17. Этапы работ

1. Каркас Gradle / Spring Boot, пакеты, модель + DTO + konvert-мапперы, `WebFilter(X-User-Id)`.
2. Слой Controller + Service (бизнес-логика), Persister-интерфейсы.
3. Реализация **PG+Redis** Persister: JPA-сущности, JpaRepository, Liquibase init,
   Redis-кэш 60s + инвалидация.
4. Реализация **Couchbase** Persister: документы, CouchbaseRepository, индексы, кэш-политика.
5. Unit + integration (Testcontainers) тесты для обоих Persister → зелёные.
6. Профили `application-*.yaml`, `@Profile`-конфиги, `Dockerfile`, fat-jar.
7. Compose-файлы всех тиров + nginx + healthchecks; ручной smoke-тест каждого.
8. Сидирование, `locustfile.py`, `collect_stats.py`.
9. `run_case.py` + `cases.yaml`; прогон Тиров A и B → валидация пайплайна.
10. Полные прогоны A→C (производительность), замеры ресурсов.
11. Chaos-тесты для Тиров B и C.
12. `aggregate.py` → сравнительные таблицы / графики → выводы по гипотезам H1–H4.
13. `write_result.py` → формирование `RESULT.md` (инструкция для pptx-генератора) + ручная правка тезисов.

## 18. Зафиксированные решения

- Spring Boot **3.3.x**, Java 17, Kotlin 2.1.
- Образы: **PostgreSQL 16, Redis 7, Couchbase 7.6 Community**.
- Миграции SQL — **Liquibase** (для Couchbase — отдельный N1QL-скрипт, Liquibase не применим).
- Сидирование — **напрямую в БД**, детерминированный датасет.
- Верхняя граница масштабирования — **Тир C** (A1/A2, B1/B2, C1/C2).
- **Протокол прогона:** каждый кейс — **3 прогона по 2 минуты** (минимум 6 минут);
  перед **каждым** прогоном — полный перезапуск контейнеров (`down -v` + `up`),
  **очистка БД** и **сидирование заново**, чтобы все кейсы и прогоны стартовали на
  равных условиях. Результаты всех 3 прогонов сохраняются отдельно + агрегат;
  по каждому кейсу формируется `REPORT.md` с подробным объяснением.
- **Гипотеза H5 (pet-projects):** дополнительно оценивается resource-efficiency
  связок на малой нагрузке (Тир A).
- **Итоговый артефакт:** `RESULT.md` (корень репозитория, русский) — структурированная
  инструкция для модели-генератора презентаций PPTX; формируется скриптом
  `bench/write_result.py` из отчётов кейсов (`results/<case>/REPORT.md`) и сводного
  `report/RESEARCH_REPORT.md` с последующей ручной правкой тезисов.

## 19. RESULT.md — инструкция для генерации презентации (pptx)

`RESULT.md` (корень репозитория) — итоговый документ исследования, оформленный как
**структурированная инструкция для модели-генератора презентаций PPTX**. Он объединяет
краткую выжимку плана (цели, гипотезы, методология, стек) и все полученные результаты
и явно описывает, что и как должно попасть на слайды.

- **Формирование:** скрипт `bench/write_result.py` (русский язык) на финальном шаге
  пайплайна собирает `RESULT.md` из `results/<case>/REPORT.md` и сводного
  `report/RESEARCH_REPORT.md`, после чего проходит ручную правку тезисов под аудиторию.
- **Содержит замеры:** latency (p50/p95/p99), throughput (RPS), потребление ресурсов
  (CPU/RAM/IO), хаос-метрики (RTO, всплеск ошибок), агрегаты по 3 прогонам.

**Структура `RESULT.md`:**

1. **Контекст исследования** — тема, цель, гипотезы H1–H5, стек технологий, архитектура
   микросервиса (выжимка из PLAN.md).
2. **Методология** — тир-схема (A1/A2…C2), протокол прогона (3 прогона × 2 мин,
   перезапуск контейнеров + ресид перед каждым прогоном, 1000/3000 пользователей,
   профили нагрузки mixed/read-heavy/write-heavy), способ сбора метрик.
3. **Результаты по каждому кейсу** — топология, таблицы latency и throughput,
   потребление ресурсов, хаос-метрики, вердикт (переносятся из `results/<case>/REPORT.md`).
4. **Сравнительные выводы по тирам** — PG+Redis vs Couchbase внутри тира, вердикты по
   H1–H5 (подтверждена/опровергнута + доказательство цифрами).
5. **Инструкция для слайдов** — явный план презентации в формате
   «Слайд N: `<заголовок>` | ключевой тезис | данные/график | тезисы-буллеты».
   Покрывает: титул, проблема и гипотезы, методика, схема стендов, графики тиров
   A/B/C, resource-efficiency для H5, хаос-тесты, итоговая сводная таблица, выводы,
   рекомендации.
6. **Приложения** — ссылки на сырые данные (`results/`, `report/`).

Цель формата — дать презентационной модели возможность напрямую собрать PPTX из одного
самодостаточного документа без обращения к разрозненным источникам.

## 20. Фактический статус реализации и отклонения

**Что реализовано и работает end-to-end:**
- Полный микросервис (оба Persister), 16 unit + 2 integration теста — зелёные, fat-jar + Docker-образ собираются.
- Весь bench/load-инструментарий: `run_case.py`, `seed.py`(→ перенесено в приложение, см. ниже),
  `collect_stats.py`, `locustfile.py`, `aggregate.py`, `write_report.py`, `write_result.py`, `cases.yaml`,
  `chaos_test.py`; docker-compose + nginx + couchbase-setup.sh; образы `order-service` и `order-bench`.
- **Тир A полностью промерен** (3 прогона × 2 мин по протоколу §10.2): A1 и A2.

**Полные результаты всех 6 кейсов (медиана по 3 прогонам; PG — 1000 users, Couchbase — 1000 users):**

| Кейс | Тир | Стек | RPS | p50, мс | p95 | Σ CPU % | Σ RAM, МБ |
|---|---|---|---|---|---|---|---|
| A1 | A | PG standalone + Redis | ~1041 | 410 | 600 мс | 244 | 920 |
| A2 | A | Couchbase single node | ~31 | 22000 | 47 с | 577 | 1969 |
| B1 | B | PG master+replica + Redis sentinel | ~1024 | 420 | 570 мс | 244 | 1093 |
| B2 | B | Couchbase 2-node cluster | ~233 | 3000 | 6.7 с | 595 | 4097 |
| C1 | C | PG master+2replica + Redis Cluster + 2 app | ~577 | 500 | 4.8 с | 290 | 2392 |
| C2 | C | Couchbase 3-node cluster + 2 app | ~253 | 540 | 14 с | 564 | 6664 |

**Chaos (H4):**
- B1, kill `redis-master` → sentinel failover, **0.6 %** ошибок (180/27775).
- B2, kill `couchbase-node-2` → Couchbase auto-failover (replica=1), **2.2 %** ошибок (206/9355), кластер выжил.

**Ключевые выводы:**
- PG+Redis на single-node даёт ~30× больший throughput, чем Couchbase single-node (N1QL query-service — узкое место).
- HA-стек PG (B1) не снижает throughput относительно A1 (1024 vs 1041 RPS).
- **Couchbase масштабируется:** 1→2 ноды = 31→233 RPS (**×7.5**), 2→3 ноды = 233→253 RPS (прирост затухает — узкое место смещается на app/query). RAM растёт линейно (~2 ГБ на ноду).
- Couchbase от 2 app выигрывает (C2 vs B2 по latency p50).
- H5 (pet-projects): Couchbase single потребляет ~2× RAM и не держит 1000 concurrent — для малых нагрузок выгоднее PG+Redis; Couchbase оправдан только при необходимости горизонтального масштабирования.

**Отклонения от плана (зафиксировано):**
- **Kotlin 2.0.21** вместо 2.1.x — версия, на которой собран konvert 4.3.2.
- **Сидирование через само приложение** (`SeedRunner`, env `APP_SEED=true`): libcouchbase (Python SDK) не поднимал bootstrap в docker-сети, Java SDK работает; единый путь через Persister/JdbcTemplate корректен для обеих СУБД.
- **Couchbase-индексы создаются в `SeedRunner`** (Java SDK): shell-cbq ненадёжен; важен `idx_class`.
- **Couchbase multi-node (secure-join TLS)**: реализован полностью. Рецепт (критично — найдено эмпирически):
  1. Свои CA + node-сертификаты (`docker-compose/couchbase/gen-certs.sh`) с **SAN = DNS:<FQDN> + IP:<статический-IP>** (Couchbase Enterprise проверяет SAN, выключить нельзя).
  2. Каждая нода: **FQDN-hostname** (с точкой, напр. `node-1.cb.internal`) + **статический IP** в custom-сети + `aliases` для DNS + wrapper-entrypoint (`cb-node-entrypoint.sh`) копирует сертификаты в `inbox` с владельцем `couchbase`.
  3. После старта на каждой ноде: `POST /node/controller/loadTrustedCAs` + `/node/controller/reloadCertificate` (контроллеры доступны **без auth** на свежей ноде).
  4. `cluster-init` мастера, затем `server-add <FQDN>:18091` (HTTPS-порт, **не 8091**), затем `rebalance`.
  5. Приложение подключается по plain `couchbase://<FQDN>,...` (client-to-node без TLS) — менять клиент не нужно.
- **HA на стандартных образах**: bitnami deprecated (с 08.2025), поэтому B1/C1 — `postgres:16` (streaming replication через `pg_basebackup` + `00-replication.sh`/`replica-entrypoint.sh`) и `redis:7-alpine` (sentinel.conf с `resolve-hostnames yes`; redis-cluster через `redis-cli --cluster create`).
