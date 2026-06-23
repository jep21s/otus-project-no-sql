# otus-project-no-sql — Couchbase vs PostgreSQL+Redis

Исследовательский проект: сравнение двух архитектурных подходов к хранению и
кэшированию на микросервисе заказов:

- **PostgreSQL + Redis** — реляционная БД + внешний кэш (явная инвалидация);
- **Couchbase** — документная БД с интегрированным memory-first кэшем.

Сравнение ведётся по **пропускной способности**, **отказоустойчивости** и
**потреблению ресурсов** в трёх уровнях топологий: single-node, HA БД и
масштабирование (кластер + несколько инстансов приложения).

> Сопутствующие документы: [`PLAN.md`](PLAN.md) — план исследования,
> [`RESULT.md`](RESULT.md) и [`report/RESEARCH_REPORT.md`](report/RESEARCH_REPORT.md) —
> итоговые результаты, [`report/PRESENTATION.md`](report/PRESENTATION.md) — текст презентации.

---

## Стек технологий

| Слой | Технология |
|---|---|
| Язык | Kotlin 2.0.21, JDK 17 |
| Фреймворк | Spring Boot 3.3.6 (Web, Validation, Actuator) |
| Маппинг DTO↔Model↔Entity | [konvert](https://github.com/mcarneiro/konvert) 4.3.2 + KSP (compile-time, без рефлексии) |
| БД SQL | PostgreSQL 16 + Spring Data JPA |
| Кэш SQL-профиля | Redis 7 (Lettuce + commons-pool2, TTL 60 с) |
| БД NoSQL | Couchbase Server 7.6 Community + Spring Data Couchbase |
| Миграции PG | Liquibase (`src/main/resources/db/changelog/`) |
| Нагрузочное тестирование | Locust (Python, headless) |
| Мониторинг ресурсов | `docker stats` + чтение cgroup → CSV (sampling 1 Гц) |
| Балансировщик | nginx (для multi-app топологий Тира C) |
| Тесты | JUnit 5, MockK, Spring MockMvc, Testcontainers (PG / Redis / Couchbase) |
| Сборка | Gradle Kotlin DSL, `bootJar` (fat-jar) |
| Контейнеры | Docker, docker-compose v2 |

---

## Архитектура микросервиса

Поток данных строго по слоям. Бизнес-логика (`Service`) знает только интерфейс
`Persister` — ни одного ветвления по профилю в сервисном слое:

```
HTTP (DTO) ──konvert──▶ Service (Model) ──▶ Persister[interface] (Model)
                                                    │
                     ◀──konvert── Repository(Entity)─┘
                                      │
                      PG: JpaRepository + Redis      Couchbase: CouchbaseRepository
```

Профиль Spring активирует нужный `@Configuration` и набор бинов `Persister`;
альтернативная реализация просто отсутствует в контексте — выбор делается на
этапе старта приложения.

**Пакеты:**

```
com.example.orderservice
├── api
│   ├── controller   OrderController, UserController, ProductController
│   └── dto          *Dto, *Request (с валидацией Bean Validation)
├── model            Order, User, Product, OrderItem, Address (чистый Kotlin)
├── mapper           konvert-мапперы: DTO↔Model↔Entity
├── service          OrderService, UserService, ProductService (+ exception)
├── persister
│   ├── api          OrderPersister, UserPersister, ProductPersister (интерфейсы)
│   ├── cache        общая логика кэширования (TTL 60 с)
│   ├── sql          Sql*Persister + SqlCacheConfig (Redis)
│   └── couchbase    Couchbase*Persister + CouchbaseCacheConfig
├── repository
│   ├── jpa          *JpaRepository
│   └── couchbase    *CouchbaseRepository
├── entity
│   ├── jpa          *Entity, AddressEmbeddable
│   └── couchbase    *Document
├── config           PgRedisConfig (@Profile), CouchbaseConfig (@Profile), WebConfig
├── web              WebFilter (читает заголовок X-User-Id → RequestContext)
└── seed             SeedRunner (сидирование БД, env APP_SEED=true)
```

**Авторизация (исследовательский режим):** `WebFilter` читает заголовок
`X-User-Id` и кладёт `userId` в `RequestContext` (ThreadLocal). Все эндпоинты
считаются находящимися в авторизованной зоне; отдельная аутентификация не
реализуется.

---

## Доменная модель

**Сущности:** `User`, `Product`, `Order`, `OrderItem`, `Address` (embedded).

- `User`: id(UUID), username, email, firstName, lastName, createdAt, updatedAt
- `Product`: id(UUID), name, description, price(BigDecimal), stock, category, createdAt
- `Order`: id(UUID), userId, status (CREATED/PAID/SHIPPED/DELIVERED/CANCELLED),
  totalAmount, shippingAddress(Address), items(List<OrderItem>), createdAt, updatedAt
- `OrderItem`: id(UUID), productId, productName(snapshot), quantity, unitPrice(snapshot)
- `Address`: street, city, state, zipCode, country

**Разница моделирования** (легитимная — отражает сильные стороны каждой БД):

| | PostgreSQL | Couchbase |
|---|---|---|
| Модель | Нормализация | Агрегат |
| Таблицы/документы | `users`, `products`, `orders` (FK→users), `order_items` (FK→orders, FK→products); адрес встроен в `orders` | Документ `order::{id}` хранит `items` вложенным массивом + адрес встроенным; документы `user::{id}`, `product::{id}` |
| Индексы | `orders(user_id, created_at desc)`, `order_items(order_id)`, уникальные `username`, `email` | N1QL: primary, на `type`, `userId`, `status` |

Слой `Persister` скрывает это различие от `Service` — что само по себе является
предметом исследования (сложность кода в двух реализациях).

---

## REST API

Все эндпоинты требуют заголовок `X-User-Id`. Метод «мои заказы» кэшируется на
уровне `Persister` с TTL = 60 с.

| Метод | URL | Операция | Кэш |
|---|---|---|---|
| POST | `/api/orders` | создать заказ | invalidate |
| GET | `/api/orders/{id}` | получить заказ | read (опционально) |
| GET | `/api/orders` | мои заказы (userId из заголовка) | **60 с** |
| PATCH | `/api/orders/{id}/status` | сменить статус | invalidate |
| DELETE | `/api/orders/{id}` | удалить заказ | invalidate |
| GET | `/api/users/me` | профиль пользователя | — |
| GET | `/api/products` | каталог товаров | — |
| GET | `/api/products/{id}` | карточка товара | — |

**Инвалидация кэша:** при create/update/delete заказа пользователя ключ
`orders:user:{userId}` удаляется из кэша. В Couchbase применяется та же явная
политика 60 с (отдельный ключ с expiry), плюс фиксируется режим «только
встроенный кэш» — часть сравнения «1 система vs 2».

---

## Проверяемые кейсы

Каждый тир — пара PG+Redis vs Couchbase при одинаковом нагрузочном профиле и
числе инстансов приложения. Всего **6 топологий** + **chaos-тесты** для Тиров B/C.

| Кейс | Тир | Стек | Назначение | Compose-файл |
|---|---|---|---|---|
| **A1** | A — single | PostgreSQL standalone + Redis standalone | базовое сравнение | `docker-compose/a1-pg-redis-single.yml` |
| **A2** | A — single | Couchbase single node | базовое сравнение | `docker-compose/a2-couchbase-single.yml` |
| **B1** | B — HA БД | PG master + 1 replica + Redis (master + replica + 3 sentinel) | отказоустойчивость | `docker-compose/b1-pg-redis-ha.yml` |
| **B2** | B — HA БД | Couchbase 2-node cluster (secure-join TLS) | отказоустойчивость | `docker-compose/b2-couchbase-2node.yml` |
| **C1** | C — scaled | PG master + 2 replica + Redis Cluster (3 master + 3 replica) + 2 app за nginx | горизонтальное масштабирование | `docker-compose/c1-pg-redis-cluster.yml` |
| **C2** | C — scaled | Couchbase 3-node cluster (secure-join TLS) + 2 app за nginx | горизонтальное масштабирование | `docker-compose/c2-couchbase-3node.yml` |

### Chaos-тесты (отказоустойчивость)

Для Тиров B и C (`bench/chaos_test.py`):

1. Старт нагрузки → ожидание steady-state (~30 с).
2. `docker stop` одной реплики/узла посреди нагрузки (отдельный прогон — убийство master/primary).
3. Замер: всплеск ошибок, время до восстановления обслуживания (**RTO**), потеря запросов.
4. `docker start` узла → замер времени ребаланса / синхронизации.

Проверяемые сценарии: PG — убийство master → failover через реплику; Redis —
sentinel-failover; Couchbase — auto-failover (vBucket rebalance).

---

## Гипотезы

| # | Гипотеза | Вердикт по итогам замеров |
|---|---|---|
| **H1** | Couchbase (memory-first) даёт меньшую латентность чтения «из коробки», чем PG+Redis | ❌ Опровергнута (на single-node узкое место — N1QL query-service) |
| **H2** | PG+Redis сложнее в эксплуатации, но предсказуемее по латентности записи под нагрузкой | ✅ Подтверждена |
| **H3** | Couchbase-кластер линейнее растёт по throughput при масштабировании (но больше RAM) | ⚠️ Частично (1→2 узла ×7.5, дальше затухание) |
| **H4** | При отказе узла Couchbase восстанавливается быстрее, чем PG+Redis | ⚠️ Паритет (обе устойчивы, RTO ≈ 0) |
| **H5** | Для pet-проектов (Тир A) Couchbase выгоднее по совокупному потреблению ресурсов | ❌ Опровергнута |

---

## Методология прогона

Каждый кейс прогоняется по протоколу, гарантирующему воспроизводимость:

- **3 прогона подряд** × **2 минуты steady-state** на прогон (минимум 6 минут на кейс);
- перед **каждым** прогоном — полный перезапуск контейнеров `docker compose down -v` + `up -d --wait`,
  **очистка БД** и **пересидирование** детерминированного датасета;
- **cool-down 30 с** между прогонами (стабилизация ресурсов, сброс встроенных кэшей БД).

**Датасет:** 1000 users, 200 products, ~10 000 orders со items (фиксированный seed RNG).

**Нагрузка:** Locust, 1000 одновременных пользователей (spawn-rate 25/с), смешанный
профиль (`mixed`, 80 % чтение / 20 % запись). Также доступны `read-heavy` (95/5)
и `write-heavy` (30/70).

**Собираемые метрики:**

- Производительность: RPS, latency p50/p90/p95/p99, % ошибок — по каждой операции;
- Ресурсы: CPU% (от числа ядер), memory, network RX/TX, disk I/O каждого контейнера (`docker stats`, 1 Гц);
- Отказоустойчивость: длительность всплеска ошибок, число упавших запросов, RTO, факт потери данных.

Результаты каждого прогона пишутся в `results/<case>/run-N/`, агрегат по 3 прогонам —
в `results/<case>/summary/`, нарратив с объяснениями — в `results/<case>/REPORT.md`.

---

## Ключевые результаты

Медиана по 3 прогонам, профиль mixed, 1000 одновременных пользователей.

| Кейс | Стек | RPS | p50, мс | p95 | Σ CPU, % | Σ RAM, МБ |
|---|---|---:|---:|---:|---:|---:|
| **A1** | PG standalone + Redis | **1041** | 410 | 600 мс | 244 | 920 |
| **A2** | Couchbase single node | 31 | 22 000 | 47 с | 577 | 1969 |
| **B1** | PG master+replica + Redis sentinel | **1024** | 420 | 570 мс | 244 | 1093 |
| **B2** | Couchbase 2-node cluster | 233 | 3000 | 6.7 с | 595 | 4097 |
| **C1** | PG + 2 replica + Redis Cluster + 2 app | 577 | 500 | 4.8 с | 290 | 2392 |
| **C2** | Couchbase 3-node cluster + 2 app | 253 | 540 | 14 с | 564 | 6664 |

**Chaos-тесты (H4):**

- **B1**, kill `redis-master` → sentinel failover, **0.6 %** ошибок (180 / 27 775).
- **B2**, kill `couchbase-node-2` → Couchbase auto-failover (replica=1), **2.2 %** ошибок (206 / 9355), кластер выжил.

**Главные выводы:**

- PG+Redis на single-node даёт ~33× больший throughput, чем Couchbase single-node
  (узкое место Couchbase — N1QL query-service на одном узле).
- HA-надстройка PG (B1) практически бесплатна: 1024 vs 1041 RPS.
- Couchbase масштабируется: 1→2 ноды = 31→233 RPS (×7.5), 2→3 ноды = 233→253 RPS
  (отдача затухает; RAM растёт линейно, ~2 ГБ на узел).
- Для pet-проектов и малых нагрузок выгоднее PG+Redis (вдвое дешевле по RAM и
  быстрее); Couchbase оправдан при горизонтальном масштабировании и документной модели.

Полные результаты и графики — в [`RESULT.md`](RESULT.md) и [`report/RESEARCH_REPORT.md`](report/RESEARCH_REPORT.md).

---

## Quickstart

### Требования

- Docker 24+ и docker-compose v2;
- хост **≈ 8 CPU / 30 GB RAM** (потолок масштабирования — Тир C);
- JDK 17 и интернет (для первой сборки Gradle) — только если собираете jar локально, а не через Docker;
- Python 3.11+ и зависимости оркестратора:
  ```bash
  pip install -r bench/requirements.txt
  ```

### 1. Сборка образов

```bash
# fat-jar приложения
./gradlew bootJar

# образ приложения order-service:latest
docker build -t order-service:latest .

# образ нагрузочного стенда order-bench:latest (Locust + bench-скрипты)
docker build -t order-bench:latest -f bench/Dockerfile bench/
```

### 2. Ручной запуск отдельного кейса (smoke)

Поднимает стенд и публикует приложение на `http://localhost:8080`:

```bash
# Тир A1: PostgreSQL + Redis (профиль активируется в compose через SPRING_PROFILES_ACTIVE)
docker compose -f docker-compose/a1-pg-redis-single.yml -p orders-a1 up -d --wait

# проверка health
curl -s http://localhost:8080/actuator/health | jq

# пример запроса (заголовок X-User-Id обязателен)
curl -s -H "X-User-Id: <user-uuid>" http://localhost:8080/api/orders | jq

# остановка и очистка
docker compose -f docker-compose/a1-pg-redis-single.yml -p orders-a1 down -v
```

Аналогично для остальных топологий — просто подставьте нужный compose-файл
(`a2-couchbase-single.yml`, `b1-pg-redis-ha.yml` и т.д.).

### 3. Полный прогон кейса через оркестратор

Выполняет полный протокол: 3 прогона × 2 мин с перезапуском контейнеров,
очисткой БД и пересидированием перед каждым прогоном, сбором метрик и
генерацией отчёта.

```bash
# полный прогон кейса A1 (3 прогона × 2 мин, 1000 пользователей, профиль mixed)
python bench/run_case.py a1

# переопределение параметров
python bench/run_case.py b2 --users 1000 --profile write-heavy --runs 3

# быстрый smoke-прогон для валидации стенда (1 прогон × 15 с)
python bench/run_case.py c2 --smoke
```

Доступные кейсы: `a1`, `a2`, `b1`, `b2`, `c1`, `c2` (см. `bench/cases.yaml`).

Результаты появятся в `results/<case>/` (`run-1|2|3/`, `summary/`, `REPORT.md`).
Сводный отчёт по всем прогоненным кейсам формируется в `report/RESEARCH_REPORT.md`.

### 4. Тесты (unit + integration)

```bash
./gradlew test
```

Покрытие: unit-тесты (`OrderServiceTest`, `SqlOrderPersisterTest`,
`OrderControllerMvcTest` на MockMvc) и интеграционные тесты на Testcontainers
(`SqlPersisterIT` — контейнеры PostgreSQL + Redis; `CouchbasePersisterIT` —
контейнер Couchbase, N1QL, expiry кэша).

---

## Структура репозитория

```
otus-project-no-sql/
├── PLAN.md, RESULT.md               # план и итоги исследования
├── build.gradle.kts                 # Gradle (Spring Boot + KSP для konvert)
├── Dockerfile                       # мультистейдж-сборка order-service:latest
├── docker-compose/                  # 6 топологий + bench-run.yml + nginx.conf
│   ├── a1-pg-redis-single.yml … c2-couchbase-3node.yml
│   ├── couchbase/                   # gen-certs.sh, entrypoint-скрипты (secure-join TLS)
│   ├── pg/, redis/                  # конфиги HA (streaming replication, sentinel, cluster)
│   └── nginx.conf                   # балансировка для multi-app Тира C
├── src/main/kotlin/com/example/orderservice/   # слои приложения (см. «Архитектура»)
├── src/main/resources/
│   ├── application.yaml             # общие настройки
│   ├── application-a1 … c2.yaml     # datasource/redis/couchbase, пулы, профиль
│   ├── db/changelog/                # Liquibase (миграции PG)
│   └── couchbase/                   # couchbase-init.sh (bucket, индексы N1QL)
├── src/test/kotlin/.../             # unit + Testcontainers IT
├── load/locustfile.py               # сценарии нагрузки (mixed / read-heavy / write-heavy)
├── bench/                           # Python-оркестрация
│   ├── run_case.py                  # CLI прогона кейса (3 прогона, restart+clean+seed)
│   ├── cases.yaml                   # описание всех кейсов (compose, профиль, users, runs)
│   ├── seed.py, clean_db.py         # сидирование и очистка БД
│   ├── collect_stats.py             # фоновый сбор docker stats + cgroup → CSV
│   ├── chaos_test.py                # инъекция отказов
│   ├── aggregate.py                 # агрегат по 3 прогонам + графики
│   └── write_report.py, write_result.py   # генерация REPORT.md / RESULT.md
├── results/                         # метрики по кейсам (run-N/, summary/, REPORT.md)
├── report/                          # RESEARCH_REPORT.md, PRESENTATION.md, presentation.pptx
└── tools/                           # gen_pptx.py, charts/
```
