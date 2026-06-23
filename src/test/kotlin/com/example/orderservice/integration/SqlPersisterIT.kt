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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["spring.profiles.active=a1"])
class SqlPersisterIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

        @Container
        @JvmStatic
        val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }

        init {
            Startables.deepStart(postgres, redis).join()
        }
    }

    @Autowired private lateinit var userPersister: UserPersister
    @Autowired private lateinit var productPersister: ProductPersister
    @Autowired private lateinit var orderPersister: OrderPersister
    @Autowired private lateinit var cache: OrderCache

    private val now = Instant.parse("2025-01-01T00:00:00Z")

    @Test
    fun `crud round trip with redis cache`() {
        val userId = UUID.randomUUID()
        userPersister.save(User(userId, "u$userId", "u$userId@mail.com", "f", "l", now, now))
        val product = productPersister.saveAll(
            listOf(Product(UUID.randomUUID(), "Widget", "desc", BigDecimal("5.00"), 100, "cat", now))
        ).first()

        val order = Order(
            id = UUID.randomUUID(),
            userId = userId,
            status = OrderStatus.CREATED,
            totalAmount = BigDecimal("10.00"),
            shippingAddress = Address("s", "c", "st", "zip", "co"),
            items = listOf(OrderItem(UUID.randomUUID(), product.id, "Widget", 2, BigDecimal("5.00"))),
            createdAt = now,
            updatedAt = now
        )
        val saved = orderPersister.save(order)
        assertEquals(order.id, saved.id)

        val found = orderPersister.findById(order.id)
        assertNotNull(found)
        assertEquals(1, found!!.items.size)
        assertEquals("Widget", found.items[0].productName)

        // my orders: first call populates cache
        assertNull(cache.getList(userId, 20, 0))
        val first = orderPersister.findByUserId(userId, 20, 0)
        assertEquals(1, first.size)
        // second call served from cache
        assertNotNull(cache.getList(userId, 20, 0))
        val second = orderPersister.findByUserId(userId, 20, 0)
        assertEquals(first.map { it.id }, second.map { it.id })

        // update status invalidates cache
        orderPersister.save(saved.copy(status = OrderStatus.PAID, updatedAt = now.plusSeconds(60)))
        assertNull(cache.getList(userId, 20, 0))
        val afterUpdate = orderPersister.findByUserId(userId, 20, 0)
        assertEquals(OrderStatus.PAID, afterUpdate[0].status)

        // delete
        assertTrue(orderPersister.deleteById(order.id))
        assertNull(orderPersister.findById(order.id))
    }
}
