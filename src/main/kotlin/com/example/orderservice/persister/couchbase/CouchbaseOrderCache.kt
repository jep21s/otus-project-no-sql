package com.example.orderservice.persister.couchbase

import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.java.json.JsonObject
import com.couchbase.client.java.kv.UpsertOptions
import com.couchbase.client.java.query.QueryOptions
import com.example.orderservice.model.Order
import com.example.orderservice.persister.cache.OrderCache
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Profile
import org.springframework.data.couchbase.core.CouchbaseTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
@Profile("a2", "b2", "c2")
class CouchbaseOrderCache(
    private val couchbaseTemplate: CouchbaseTemplate,
    private val objectMapper: ObjectMapper
) : OrderCache {

    private val typeRef = object : TypeReference<List<Order>>() {}

    private val collection
        get() = couchbaseTemplate.couchbaseClientFactory.bucket.defaultCollection()

    override fun getList(userId: UUID, limit: Int, offset: Int): List<Order>? {
        return try {
            val doc = collection.get(key(userId, limit, offset)).contentAs(JsonObject::class.java)
            objectMapper.readValue(doc.get("v").toString(), typeRef)
        } catch (e: DocumentNotFoundException) {
            null
        }
    }

    override fun putList(userId: UUID, limit: Int, offset: Int, orders: List<Order>) {
        val doc = JsonObject.create()
            .put("cu", userId.toString())
            .put("v", objectMapper.writeValueAsString(orders))
        collection.upsert(
            key(userId, limit, offset), doc,
            UpsertOptions.upsertOptions().expiry(Duration.ofSeconds(TTL_SECONDS))
        )
    }

    override fun evictUser(userId: UUID) {
        // индексная эвикция (idx_cache_user) — без full-scan
        val bucket = couchbaseTemplate.couchbaseClientFactory.bucket.name()
        couchbaseTemplate.couchbaseClientFactory.cluster.query(
            "DELETE FROM `$bucket` WHERE `cu` = \$uid",
            QueryOptions.queryOptions().parameters(JsonObject.create().put("uid", userId.toString()))
        )
    }

    private fun key(userId: UUID, limit: Int, offset: Int) = "$PREFIX$userId:l$limit:o$offset"

    companion object {
        private const val PREFIX = "cache:orders:user:"
        const val TTL_SECONDS = 60L
    }
}
