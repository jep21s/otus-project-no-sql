"""Детерминированный тестовый датасет.
Одинаковые данные для PG и Couchbase (воспроизводимость всех кейсов).
Импортируется и seed.py, и load/locustfile.py.
"""
import random
import uuid
from datetime import datetime, timedelta, timezone

NAMESPACE = uuid.UUID("00000000-0000-0000-0000-000000000000")

USERS = 1000
PRODUCTS = 200
ORDERS = 10000

CATEGORIES = ["electronics", "books", "clothing", "home", "toys", "sports", "food"]
FIRST_NAMES = ["Alex", "Sam", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Jamie", "Drew", "Quinn"]
LAST_NAMES = ["Smith", "Johnson", "Lee", "Patel", "Garcia", "Kim", "Brown", "Davis", "Lopez", "Wong"]
CITIES = [("Moscow", "MSK"), ("Berlin", "BE"), ("London", "LDN"), ("Paris", "PAR"), ("NYC", "NY")]
EPOCH = datetime(2024, 1, 1, tzinfo=timezone.utc)


def user_id(i: int) -> uuid.UUID:
    return uuid.uuid5(NAMESPACE, f"user-{i}")


def product_id(i: int) -> uuid.UUID:
    return uuid.uuid5(NAMESPACE, f"product-{i}")


def gen_users():
    rng = random.Random(101)
    users = []
    for i in range(USERS):
        uid = user_id(i)
        users.append({
            "id": uid,
            "username": f"user{i}",
            "email": f"user{i}@example.com",
            "firstName": rng.choice(FIRST_NAMES),
            "lastName": rng.choice(LAST_NAMES),
            "createdAt": EPOCH + timedelta(seconds=i),
            "updatedAt": EPOCH + timedelta(seconds=i),
        })
    return users


def gen_products():
    rng = random.Random(202)
    products = []
    for i in range(PRODUCTS):
        pid = product_id(i)
        price_cents = 100 + (i * 37) % 5000
        products.append({
            "id": pid,
            "name": f"Product {i}",
            "description": f"Description for product {i}",
            "priceCents": price_cents,
            "stock": 1000 + (i * 13) % 500,
            "category": rng.choice(CATEGORIES),
            "createdAt": EPOCH + timedelta(seconds=i),
        })
    return products


def gen_orders():
    rng = random.Random(303)
    products = gen_products()
    orders = []
    for i in range(ORDERS):
        ou = user_id(rng.randrange(USERS))
        n_items = rng.randint(1, 5)
        chosen = rng.sample(range(PRODUCTS), n_items)
        items = []
        total_cents = 0
        for k, pidx in enumerate(chosen):
            p = products[pidx]
            qty = rng.randint(1, 3)
            total_cents += p["priceCents"] * qty
            items.append({
                "id": uuid.uuid5(NAMESPACE, f"order-{i}-item-{k}"),
                "productId": p["id"],
                "productName": p["name"],
                "quantity": qty,
                "unitPriceCents": p["priceCents"],
            })
        city, state = rng.choice(CITIES)
        created = EPOCH + timedelta(seconds=rng.randint(0, 30 * 24 * 3600))
        status = rng.choices(
            ["CREATED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"],
            weights=[10, 40, 20, 25, 5])[0]
        orders.append({
            "id": uuid.uuid5(NAMESPACE, f"order-{i}"),
            "userId": ou,
            "status": status,
            "totalCents": total_cents,
            "shippingAddress": {
                "street": f"{rng.randint(1, 200)} Main St",
                "city": city,
                "state": state,
                "zipCode": f"{10000 + rng.randint(0, 89999)}",
                "country": "WW",
            },
            "items": items,
            "createdAt": created,
            "updatedAt": created,
        })
    return orders


def cents_to_str(cents: int) -> str:
    """BigDecimal как строка 'NNN.NN'."""
    return f"{cents // 100}.{cents % 100:02d}"
