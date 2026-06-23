package com.example.orderservice.persister.sql

import com.example.orderservice.entity.jpa.OrderEntity
import com.example.orderservice.entity.jpa.OrderItemEntity
import com.example.orderservice.mapper.JpaMapperImpl
import com.example.orderservice.model.Order
import com.example.orderservice.persister.api.OrderPersister
import com.example.orderservice.persister.cache.OrderCache
import com.example.orderservice.repository.jpa.OrderJpaRepository
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Profile("a1", "b1", "c1")
@Transactional
class SqlOrderPersister(
    private val orderRepository: OrderJpaRepository,
    private val entityManager: EntityManager,
    private val cache: OrderCache
) : OrderPersister {

    @Transactional(readOnly = true)
    override fun findById(id: UUID): Order? =
        orderRepository.findById(id).orElse(null)?.let { toModel(it) }

    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID, limit: Int, offset: Int): List<Order> {
        cache.getList(userId, limit, offset)?.let { return it }
        val page = PageRequest.of(offset / limit.coerceAtLeast(1), limit)
        val orders = orderRepository.findByUserId(userId, page).map { toModel(it) }
        cache.putList(userId, limit, offset, orders)
        return orders
    }

    override fun save(order: Order): Order {
        val entity = toEntity(order)
        val saved = entityManager.merge(entity)
        entityManager.flush()
        cache.evictUser(order.userId)
        return toModel(saved)
    }

    override fun deleteById(id: UUID): Boolean {
        val entity = orderRepository.findById(id).orElse(null) ?: return false
        orderRepository.delete(entity)
        orderRepository.flush()
        cache.evictUser(entity.userId)
        return true
    }

    @Transactional(readOnly = true)
    override fun existsById(id: UUID): Boolean = orderRepository.existsById(id)

    private fun toModel(entity: OrderEntity): Order = Order(
        id = entity.id,
        userId = entity.userId,
        status = entity.status,
        totalAmount = entity.totalAmount,
        shippingAddress = JpaMapperImpl.toModel(entity.shippingAddress),
        items = entity.items.map { JpaMapperImpl.toModel(it) },
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    private fun toEntity(order: Order): OrderEntity {
        val items: MutableList<OrderItemEntity> =
            order.items.map { JpaMapperImpl.toEntity(it) }.toMutableList()
        return OrderEntity(
            id = order.id,
            userId = order.userId,
            status = order.status,
            totalAmount = order.totalAmount,
            shippingAddress = JpaMapperImpl.toEmbeddable(order.shippingAddress),
            items = items,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )
    }
}
