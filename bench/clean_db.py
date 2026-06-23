"""Очистка БД перед каждым прогоном (детерминированные равные условия).
Запускается в контейнере order-bench внутри сети compose.

Env:
  TARGET=pg|couchbase
  PG: PGHOST, PGPORT(5432), PGDATABASE(orders), PGUSER(orders), PGPASSWORD(orders)
  CB: CB_CONN, CB_USER, CB_PASS, CB_BUCKET(orders)
"""
import os
import sys

TARGET = os.environ.get("TARGET", "pg")


def clean_postgres():
    import psycopg2
    conn = psycopg2.connect(
        host=os.environ["PGHOST"], port=int(os.environ.get("PGPORT", "5432")),
        dbname=os.environ.get("PGDATABASE", "orders"),
        user=os.environ.get("PGUSER", "orders"), password=os.environ.get("PGPASSWORD", "orders"),
    )
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("TRUNCATE TABLE order_items, orders, products, users RESTART IDENTITY CASCADE;")
    conn.close()
    print("[clean] postgres truncated")


def clean_couchbase():
    from couchbase.cluster import Cluster
    from couchbase.auth import PasswordAuthenticator
    from couchbase.options import ClusterOptions, ClusterTimeoutOptions
    import datetime as _dt

    conn = os.environ["CB_CONN"]
    auth = PasswordAuthenticator(os.environ["CB_USER"], os.environ["CB_PASS"])
    cluster = Cluster(conn, ClusterOptions(auth, timeout_options=ClusterTimeoutOptions()))
    bucket = os.environ.get("CB_BUCKET", "orders")
    # ждём готовности
    for _ in range(60):
        try:
            cluster.query(f"SELECT 1 FROM `{bucket}` LIMIT 1").execute()
            break
        except Exception:
            import time
            time.sleep(1)
    # удаляем все документы приложения и кэша
    cluster.query(f"DELETE FROM `{bucket}` WHERE META().id LIKE 'cache:%'").execute()
    q = cluster.query(
        f"DELETE FROM `{bucket}` WHERE `_class` IS NOT NULL OR "
        f"`userId` IS NOT NULL OR `username` IS NOT NULL"
    )
    q.execute()
    # создаём первичный индекс на всякий случай
    try:
        cluster.query(f"CREATE PRIMARY INDEX IF NOT EXISTS ON `{bucket}`").execute()
    except Exception:
        pass
    print("[clean] couchbase cleared")


if __name__ == "__main__":
    if TARGET == "pg":
        clean_postgres()
    elif TARGET == "couchbase":
        clean_couchbase()
    else:
        print(f"unknown TARGET={TARGET}", file=sys.stderr)
        sys.exit(1)
