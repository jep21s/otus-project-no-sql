package com.example.orderservice.persister.couchbase

import com.example.orderservice.mapper.CouchbaseMapperImpl
import com.example.orderservice.model.Order
import com.example.orderservice.persister.api.OrderPersister
import com.example.orderservice.persister.cache.OrderCache
import com.example.orderservice.repository.couchbase.OrderCouchbaseRepository
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@Profile("a2", "b2", "c2")
class CouchbaseOrderPersister(
    private val orderRepository: OrderCouchbaseRepository,
    private val cache: OrderCache
) : OrderPersister {

    override fun findById(id: UUID): Order? =
        orderRepository.findById(id).orElse(null)?.let { CouchbaseMapperImpl.toModel(it) }

    override fun findByUserId(userId: UUID, limit: Int, offset: Int): List<Order> {
        cache.getList(userId, limit, offset)?.let { return it }
        val page = PageRequest.of(
            offset / limit.coerceAtLeast(1),
            limit,
            Sort.by(Sort.Direction.DESC, "createdAt")
        )
        val orders = orderRepository.findByUserId(userId, page).content.map { CouchbaseMapperImpl.toModel(it) }
        cache.putList(userId, limit, offset, orders)
        return orders
    }

    override fun save(order: Order): Order {
        val saved = orderRepository.save(CouchbaseMapperImpl.toDocument(order))
        cache.evictUser(order.userId)
        return CouchbaseMapperImpl.toModel(saved)
    }

    override fun deleteById(id: UUID): Boolean {
        val existing = orderRepository.findById(id).orElse(null) ?: return false
        orderRepository.delete(existing)
        cache.evictUser(existing.userId)
        return true
    }

    override fun existsById(id: UUID): Boolean = orderRepository.existsById(id)
}
