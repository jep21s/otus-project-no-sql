package com.example.orderservice.seed

import com.example.orderservice.model.Address
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Детерминированный датасет. UUID-ы (user/product/order) вычисляются как UUIDv5
 * (RFC 4122, SHA-1), совпадают с bench/dataset.py, чтобы Locust ссылался на реальных
 * пользователей/товары. Содержимое заказов может отличаться от python-версии — это некритично.
 */
object SeedDataset {
    private val NAMESPACE = UUID.fromString("00000000-0000-0000-0000-000000000000")
    private val EPOCH = Instant.parse("2024-01-01T00:00:00Z")

    const val USERS = 1000
    const val PRODUCTS = 200
    const val ORDERS = 10000

    private val FIRST = listOf("Alex", "Sam", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Jamie", "Drew", "Quinn")
    private val LAST = listOf("Smith", "Johnson", "Lee", "Patel", "Garcia", "Kim", "Brown", "Davis", "Lopez", "Wong")
    private val CATS = listOf("electronics", "books", "clothing", "home", "toys", "sports", "food")
    private val CITIES = listOf("Moscow" to "MSK", "Berlin" to "BE", "London" to "LDN", "Paris" to "PAR", "NYC" to "NY")
    private val STATUSES = listOf(OrderStatus.CREATED to 10, OrderStatus.PAID to 40,
        OrderStatus.SHIPPED to 20, OrderStatus.DELIVERED to 25, OrderStatus.CANCELLED to 5)

    fun userId(i: Int): UUID = uuidV5(NAMESPACE, "user-$i")
    fun productId(i: Int): UUID = uuidV5(NAMESPACE, "product-$i")

    fun users(): List<User> {
        val r = Random(101)
        return (0 until USERS).map { i ->
            val t = EPOCH.plusSeconds(i.toLong())
            User(userId(i), "user$i", "user$i@example.com",
                r.nextFrom(FIRST), r.nextFrom(LAST), t, t)
        }
    }

    fun products(): List<Product> {
        val r = Random(202)
        return (0 until PRODUCTS).map { i ->
            val cents = 100 + (i * 37) % 5000
            Product(productId(i), "Product $i", "Description for product $i",
                cents(cents), 1000 + (i * 13) % 500, r.nextFrom(CATS), EPOCH.plusSeconds(i.toLong()))
        }
    }

    fun orders(): List<Order> {
        val r = Random(303)
        val prods = products()
        return (0 until ORDERS).map { i ->
            val nItems = r.nextInt(1, 6)
            val chosen = r.ints(nItems, 0, PRODUCTS).distinct().toList()
            val items = chosen.mapIndexed { k, pidx ->
                val p = prods[pidx]
                val qty = r.nextInt(1, 4)
                OrderItem(uuidV5(NAMESPACE, "order-$i-item-$k"), p.id, p.name, qty, p.price)
            }
            val total = items.fold(BigDecimal.ZERO) { acc, it -> acc + it.unitPrice * BigDecimal(it.quantity) }
            val (city, state) = r.nextFrom(CITIES)
            val created = EPOCH.plusSeconds(r.nextLong(0, 30L * 24 * 3600))
            Order(uuidV5(NAMESPACE, "order-$i"), userId(r.nextInt(USERS)), r.weighted(STATUSES),
                total, Address("${r.nextInt(1, 201)} Main St", city, state, "${10000 + r.nextInt(0, 89999)}", "WW"),
                items, created, created)
        }
    }

    fun cents(c: Int) = BigDecimal("${c / 100}.${(c % 100).toString().padStart(2, '0')}")

    private fun <T> Random.nextFrom(list: List<T>) = list[nextInt(list.size)]
    private fun <T> Random.weighted(pairs: List<Pair<T, Int>>): T {
        val total = pairs.sumOf { it.second }
        var x = nextInt(total)
        for ((v, w) in pairs) { if (x < w) return v; x -= w }
        return pairs.first().first
    }
    private fun Random.ints(n: Int, origin: Int, bound: Int) = (1..n).map { nextInt(origin, bound) }
    private fun Random.nextLong(origin: Long, bound: Long): Long {
        if (origin >= bound) return origin
        val span = bound - origin
        return origin + (nextLong() and Long.MAX_VALUE) % span
    }

    /** RFC 4122 UUID v5 (SHA-1). Совпадает с python uuid.uuid5. */
    private fun uuidV5(namespace: UUID, name: String): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(toBytes(namespace))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hash = md.digest()
        val b = hash.copyOf(16)
        b[6] = (b[6].toInt() and 0x0f or 0x50).toByte()
        b[8] = (b[8].toInt() and 0x3f or 0x80).toByte()
        return toUUID(b)
    }

    private fun toBytes(uuid: UUID): ByteArray {
        val bb = ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private fun toUUID(b: ByteArray): UUID {
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long)
    }
}
