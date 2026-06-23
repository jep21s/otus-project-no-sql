package com.example.orderservice.integration

import com.example.orderservice.model.Address
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import com.example.orderservice.persister.api.OrderPersister
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.persister.api.UserPersister
import com.example.orderservice.persister.cache.OrderCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["spring.profiles.active=a2"])
class CouchbasePersisterIT {

    companion object {
        @Container
        @JvmStatic
        val couchbase = CouchbaseContainer(DockerImageName.parse("couchbase/server:7.6.0"))
            .withBucket(BucketDefinition("orders"))

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.couchbase.connection-string") { couchbase.connectionString }
            registry.add("spring.couchbase.username") { couchbase.username }
            registry.add("spring.couchbase.password") { couchbase.password }
            registry.add("spring.data.couchbase.bucket-name") { "orders" }
        }
    }

    @Autowired private lateinit var userPersister: UserPersister
    @Autowired private lateinit var productPersister: ProductPersister
    @Autowired private lateinit var orderPersister: OrderPersister
    @Autowired private lateinit var cache: OrderCache

    private val now = Instant.parse("2025-01-01T00:00:00Z")

    @Test
    fun `crud round trip with couchbase cache`() {
        val userId = UUID.randomUUID()
        userPersister.save(User(userId, "u$userId", "u$userId@mail.com", "f", "l", now, now))
        val product = productPersister.saveAll(
            listOf(Product(UUID.randomUUID(), "Gadget", "desc", BigDecimal("7.50"), 50, "cat", now))
        ).first()

        val order = Order(
            id = UUID.randomUUID(),
            userId = userId,
            status = OrderStatus.CREATED,
            totalAmount = BigDecimal("15.00"),
            shippingAddress = Address("s", "c", "st", "zip", "co"),
            items = listOf(OrderItem(UUID.randomUUID(), product.id, "Gadget", 2, BigDecimal("7.50"))),
            createdAt = now,
            updatedAt = now
        )
        val saved = orderPersister.save(order)
        assertEquals(order.id, saved.id)

        val found = orderPersister.findById(order.id)
        assertNotNull(found)
        assertEquals(1, found!!.items.size)
        assertEquals("Gadget", found.items[0].productName)

        // my orders populates cache
        assertNull(cache.getList(userId, 20, 0))
        val first = orderPersister.findByUserId(userId, 20, 0)
        assertEquals(1, first.size)
        assertNotNull(cache.getList(userId, 20, 0))

        // update invalidates cache
        orderPersister.save(saved.copy(status = OrderStatus.SHIPPED, updatedAt = now.plusSeconds(60)))
        assertNull(cache.getList(userId, 20, 0))
        val afterUpdate = orderPersister.findByUserId(userId, 20, 0)
        assertEquals(OrderStatus.SHIPPED, afterUpdate[0].status)

        assertTrue(orderPersister.deleteById(order.id))
        assertNull(orderPersister.findById(order.id))
    }
}
