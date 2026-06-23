package com.example.orderservice.unit

import com.example.orderservice.entity.jpa.AddressEmbeddable
import com.example.orderservice.entity.jpa.OrderEntity
import com.example.orderservice.entity.jpa.OrderItemEntity
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.persister.cache.OrderCache
import com.example.orderservice.persister.sql.SqlOrderPersister
import com.example.orderservice.repository.jpa.OrderJpaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SqlOrderPersisterTest {

    private val repository = mockk<OrderJpaRepository>()
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val cache = mockk<OrderCache>(relaxed = true)

    private val persister = SqlOrderPersister(repository, entityManager, cache)

    private val userId = UUID.randomUUID()

    private fun sampleEntity(id: UUID = UUID.randomUUID()): OrderEntity = OrderEntity(
        id = id,
        userId = userId,
        status = OrderStatus.CREATED,
        totalAmount = BigDecimal("9.99"),
        shippingAddress = AddressEmbeddable("s", "c", "st", "z", "co"),
        items = mutableListOf(
            OrderItemEntity(UUID.randomUUID(), UUID.randomUUID(), "p", 1, BigDecimal("9.99"))
        ),
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2025-01-01T00:00:00Z")
    )

    @Test
    fun `findByUserId hits cache and skips db`() {
        val orderId = UUID.randomUUID()
        val cached = listOf(
            Order(orderId, userId, OrderStatus.CREATED, BigDecimal("9.99"),
                com.example.orderservice.model.Address("s", "c", "st", "z", "co"),
                emptyList(), Instant.EPOCH, Instant.EPOCH)
        )
        every { cache.getList(userId, 20, 0) } returns cached

        val result = persister.findByUserId(userId, 20, 0)

        assertEquals(cached, result)
        verify(exactly = 0) { repository.findByUserId(any(), any()) }
    }

    @Test
    fun `findByUserId miss loads from db and puts to cache`() {
        every { cache.getList(userId, 20, 0) } returns null
        val entity = sampleEntity()
        val page = PageRequest.of(0, 20)
        every { repository.findByUserId(userId, page) } returns listOf(entity)

        val result = persister.findByUserId(userId, 20, 0)

        assertEquals(1, result.size)
        assertEquals(entity.id, result[0].id)
        verify(exactly = 1) { cache.putList(userId, 20, 0, any()) }
    }

    @Test
    fun `save merges and evicts user cache`() {
        val model = Order(
            UUID.randomUUID(), userId, OrderStatus.PAID, BigDecimal("9.99"),
            com.example.orderservice.model.Address("s", "c", "st", "z", "co"),
            emptyList(), Instant.EPOCH, Instant.EPOCH
        )
        val entity = sampleEntity(model.id)
        every { entityManager.merge(any<OrderEntity>()) } returns entity

        val saved = persister.save(model)

        assertEquals(model.id, saved.id)
        verify { entityManager.merge(any<OrderEntity>()) }
        verify { cache.evictUser(userId) }
    }

    @Test
    fun `deleteById removes and evicts user cache`() {
        val entity = sampleEntity()
        every { repository.findById(entity.id) } returns Optional.of(entity)
        every { repository.delete(entity) } returns Unit
        every { repository.flush() } returns Unit

        val deleted = persister.deleteById(entity.id)

        assertEquals(true, deleted)
        verifyOrder {
            repository.findById(entity.id)
            repository.delete(entity)
            cache.evictUser(userId)
        }
    }

    @Test
    fun `deleteById returns false when absent`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns Optional.empty()
        assertEquals(false, persister.deleteById(id))
    }
}
