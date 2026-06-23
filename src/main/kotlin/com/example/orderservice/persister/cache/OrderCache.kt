package com.example.orderservice.persister.cache

import com.example.orderservice.model.Order
import java.util.UUID

/**
 * Кэш списка «мои заказы» на слое Persister (TTL 60s). Реализации:
 * [com.example.orderservice.persister.sql.RedisOrderCache] (PG+Redis profile) и
 * [com.example.orderservice.persister.couchbase.CouchbaseOrderCache] (Couchbase profile).
 */
interface OrderCache {

    fun getList(userId: UUID, limit: Int, offset: Int): List<Order>?

    fun putList(userId: UUID, limit: Int, offset: Int, orders: List<Order>)

    fun evictUser(userId: UUID)

    companion object {
        const val TTL_SECONDS = 60L
    }
}
