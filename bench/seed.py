"""Сидирование детерминированного датасета напрямую в БД.
Запускается в контейнере order-bench внутри сети compose.

Env: TARGET=pg|couchbase (+ connection env, см. clean_db.py).
Объём: 1000 users, 200 products, ~10000 orders.
"""
import os
import sys
import time

sys.path.insert(0, "/bench")
from dataset import gen_users, gen_products, gen_orders, cents_to_str  # noqa: E402

TARGET = os.environ.get("TARGET", "pg")


def seed_postgres():
    import psycopg2
    from psycopg2.extras import execute_values

    conn = psycopg2.connect(
        host=os.environ["PGHOST"], port=int(os.environ.get("PGPORT", "5432")),
        dbname=os.environ.get("PGDATABASE", "orders"),
        user=os.environ.get("PGUSER", "orders"), password=os.environ.get("PGPASSWORD", "orders"),
    )
    conn.autocommit = True
    cur = conn.cursor()

    users = gen_users()
    execute_values(cur, """
        INSERT INTO users (id, username, email, first_name, last_name, created_at, updated_at)
        VALUES %s
    """, [(str(u["id"]), u["username"], u["email"], u["firstName"], u["lastName"],
           u["createdAt"], u["updatedAt"]) for u in users], page_size=1000)
    print(f"[seed] users={len(users)}")

    products = gen_products()
    execute_values(cur, """
        INSERT INTO products (id, name, description, price, stock, category, created_at)
        VALUES %s
    """, [(str(p["id"]), p["name"], p["description"], cents_to_str(p["priceCents"]),
           p["stock"], p["category"], p["createdAt"]) for p in products], page_size=500)
    print(f"[seed] products={len(products)}")

    orders = gen_orders()
    execute_values(cur, """
        INSERT INTO orders
        (id, user_id, status, total_amount, ship_street, ship_city, ship_state, ship_zip, ship_country, created_at, updated_at)
        VALUES %s
    """, [(str(o["id"]), str(o["userId"]), o["status"], cents_to_str(o["totalCents"]),
           o["shippingAddress"]["street"], o["shippingAddress"]["city"],
           o["shippingAddress"]["state"], o["shippingAddress"]["zipCode"],
           o["shippingAddress"]["country"], o["createdAt"], o["updatedAt"])
          for o in orders], page_size=500)
    print(f"[seed] orders={len(orders)}")

    item_rows = []
    for o in orders:
        for it in o["items"]:
            item_rows.append((str(it["id"]), str(o["id"]), str(it["productId"]),
                              it["productName"], it["quantity"], cents_to_str(it["unitPriceCents"])))
    execute_values(cur, """
        INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price)
        VALUES %s
    """, item_rows, page_size=2000)
    print(f"[seed] order_items={len(item_rows)}")

    cur.close()
    conn.close()


def _iso(dt) -> str:
    return dt.astimezone().isoformat().replace("+00:00", "Z")


def seed_couchbase():
    from couchbase.cluster import Cluster
    from couchbase.auth import PasswordAuthenticator
    from couchbase.options import ClusterOptions, ClusterTimeoutOptions

    conn = os.environ["CB_CONN"]
    auth = PasswordAuthenticator(os.environ["CB_USER"], os.environ["CB_PASS"])
    cluster = Cluster(conn, ClusterOptions(auth, timeout_options=ClusterTimeoutOptions()))
    bucket = os.environ.get("CB_BUCKET", "orders")
    collection = cluster.bucket(bucket).default_collection()

    PKGS = "com.example.orderservice.entity.couchbase"

    def upsert_batch(docs):
        # docs: list of (key, dict)
        from couchbase.options import UpsertOptions
        for key, content in docs:
            collection.upsert(key, content)

    users = gen_users()
    upsert_batch([
        (str(u["id"]), {
            "_class": f"{PKGS}.UserDocument",
            "username": u["username"], "email": u["email"],
            "firstName": u["firstName"], "lastName": u["lastName"],
            "createdAt": _iso(u["createdAt"]), "updatedAt": _iso(u["updatedAt"]),
        }) for u in users
    ])
    print(f"[seed] users={len(users)}")

    products = gen_products()
    upsert_batch([
        (str(p["id"]), {
            "_class": f"{PKGS}.ProductDocument",
            "name": p["name"], "description": p["description"],
            "price": cents_to_str(p["priceCents"]), "stock": p["stock"],
            "category": p["category"], "createdAt": _iso(p["createdAt"]),
        }) for p in products
    ])
    print(f"[seed] products={len(products)}")

    orders = gen_orders()
    upsert_batch([
        (str(o["id"]), {
            "_class": f"{PKGS}.OrderDocument",
            "userId": str(o["userId"]),
            "status": o["status"],
            "totalAmount": cents_to_str(o["totalCents"]),
            "shippingAddress": o["shippingAddress"],
            "items": [{
                "id": str(it["id"]), "productId": str(it["productId"]),
                "productName": it["productName"], "quantity": it["quantity"],
                "unitPrice": cents_to_str(it["unitPriceCents"]),
            } for it in o["items"]],
            "createdAt": _iso(o["createdAt"]), "updatedAt": _iso(o["updatedAt"]),
        }) for o in orders
    ])
    print(f"[seed] orders={len(orders)}")

    # индексы для seeded-данных
    try:
        cluster.query(
            f"CREATE INDEX IF NOT EXISTS idx_order_uid ON `{bucket}`(userId) "
            f"WHERE `_class` LIKE '%OrderDocument%'"
        ).execute()
        cluster.query(
            f"CREATE INDEX IF NOT EXISTS idx_order_uid_created ON `{bucket}`(userId, createdAt DESC) "
            f"WHERE `_class` LIKE '%OrderDocument%'"
        ).execute()
    except Exception as e:
        print(f"[seed] index warn: {e}")


if __name__ == "__main__":
    t0 = time.time()
    if TARGET == "pg":
        seed_postgres()
    elif TARGET == "couchbase":
        seed_couchbase()
    else:
        print(f"unknown TARGET={TARGET}", file=sys.stderr)
        sys.exit(1)
    print(f"[seed] done in {time.time() - t0:.1f}s")
