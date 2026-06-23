package com.example.orderservice.persister.sql

import com.example.orderservice.model.Order
import com.example.orderservice.persister.cache.OrderCache
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@Profile("a1", "b1", "c1")
class RedisOrderCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) : OrderCache {

    private val typeRef = object : TypeReference<List<Order>>() {}

    override fun getList(userId: UUID, limit: Int, offset: Int): List<Order>? {
        val json = redisTemplate.opsForValue()[key(userId, limit, offset)] ?: return null
        return objectMapper.readValue(json, typeRef)
    }

    override fun putList(userId: UUID, limit: Int, offset: Int, orders: List<Order>) {
        redisTemplate.opsForValue()
            .set(key(userId, limit, offset), objectMapper.writeValueAsString(orders), OrderCache.TTL_SECONDS, TimeUnit.SECONDS)
    }

    override fun evictUser(userId: UUID) {
        val keys = redisTemplate.keys("$PREFIX$userId:*")
        if (!keys.isNullOrEmpty()) {
            redisTemplate.delete(keys)
        }
    }

    private fun key(userId: UUID, limit: Int, offset: Int) = "$PREFIX$userId:l$limit:o$offset"

    companion object {
        private const val PREFIX = "orders:user:"
    }
}
