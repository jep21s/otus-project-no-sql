"""Locust-нагрузка на микросервис заказов.
Профиль нагрузки: env LOCUST_PROFILE = mixed (по умолчанию) | read_heavy | write_heavy.
userId берутся из детерминированного пула 1000 пользователей (dataset).
Запускается в контейнере order-bench внутри сети compose (--host http://app:8080).
"""
import os
import random
import sys

sys.path.insert(0, "/bench")
from dataset import USERS, PRODUCTS, user_id, product_id  # noqa: E402

from locust import HttpUser, task, between

PROFILE = os.environ.get("LOCUST_PROFILE", "mixed")

# детерминированные пулы идентификаторов
USER_IDS = [str(user_id(i)) for i in range(USERS)]
PRODUCT_IDS = [str(product_id(i)) for i in range(PRODUCTS)]
ADDR = {"street": "1 Test St", "city": "Test", "state": "TS", "zipCode": "00000", "country": "WW"}
CITIES = ["Moscow", "Berlin", "London", "Paris", "NYC"]

# веса задач по профилям (read vs write)
WEIGHTS = {
    "mixed":      {"my_orders": 35, "user_me": 8, "list_products": 12, "get_product": 25, "create": 10, "update": 5, "delete": 5},
    "read_heavy": {"my_orders": 50, "user_me": 10, "list_products": 15, "get_product": 20, "create": 2, "update": 1, "delete": 2},
    "write_heavy":{"my_orders": 10, "user_me": 4, "list_products": 6, "get_product": 10, "create": 35, "update": 18, "delete": 17},
}
W = WEIGHTS.get(PROFILE, WEIGHTS["mixed"])


class OrderUser(HttpUser):
    wait_time = between(0.1, 0.5)
    host = os.environ.get("LOCUST_HOST", "http://app:8080")

    def on_start(self):
        self.created = []
        self.my_uid = random.choice(USER_IDS)
        self.client.headers.update({"Content-Type": "application/json"})

    def _uid(self):
        return self.my_uid

    @task
    def my_orders(self):
        with self.client.get("/api/orders?limit=20&offset=0", headers={"X-User-Id": self._uid()},
                             name="GET /api/orders (cached)", catch_response=True) as r:
            if r.status_code == 200:
                r.success()
            else:
                r.failure(f"{r.status_code}")

    @task
    def user_me(self):
        self.client.get("/api/users/me", headers={"X-User-Id": self._uid()}, name="GET /api/users/me")

    @task
    def list_products(self):
        self.client.get("/api/products?limit=20&offset=0", headers={"X-User-Id": self._uid()},
                        name="GET /api/products")

    @task
    def get_product(self):
        pid = random.choice(PRODUCT_IDS)
        self.client.get(f"/api/products/{pid}", headers={"X-User-Id": self._uid()},
                        name="GET /api/products/{id}")

    @task
    def create(self):
        uid = self._uid()
        n = random.randint(1, 3)
        items = [{"productId": random.choice(PRODUCT_IDS), "quantity": random.randint(1, 2)} for _ in range(n)]
        body = {"items": items, "shippingAddress": ADDR}
        with self.client.post("/api/orders", json=body, headers={"X-User-Id": uid},
                              name="POST /api/orders", catch_response=True) as r:
            if r.status_code == 201 and r.content:
                try:
                    oid = r.json().get("id")
                    if oid:
                        self.created.append((oid, uid))
                        if len(self.created) > 50:
                            self.created.pop(0)
                    r.success()
                except Exception:
                    r.failure("bad json")
            elif r.status_code == 201:
                r.success()
            else:
                r.failure(f"{r.status_code}")

    @task
    def update(self):
        if not self.created:
            return
        oid, uid = random.choice(self.created)
        self.client.patch(f"/api/orders/{oid}/status", json={"status": "PAID"},
                          headers={"X-User-Id": uid}, name="PATCH /api/orders/{id}/status")

    @task
    def delete(self):
        if not self.created:
            return
        oid, uid = random.choice(self.created)
        with self.client.delete(f"/api/orders/{oid}", headers={"X-User-Id": uid},
                                name="DELETE /api/orders/{id}", catch_response=True) as r:
            if r.status_code in (204, 404):
                if r.status_code == 204 and (oid, uid) in self.created:
                    self.created.remove((oid, uid))
                r.success()
            else:
                r.failure(f"{r.status_code}")


# применяем веса из выбранного профиля
def _apply_weights():
    tasks = {}
    mapping = {
        "my_orders": OrderUser.my_orders, "user_me": OrderUser.user_me,
        "list_products": OrderUser.list_products, "get_product": OrderUser.get_product,
        "create": OrderUser.create, "update": OrderUser.update, "delete": OrderUser.delete,
    }
    for name, fn in mapping.items():
        tasks[fn] = W[name]
    OrderUser.tasks = tasks


_apply_weights()
print(f"[locust] profile={PROFILE} weights={W}")
