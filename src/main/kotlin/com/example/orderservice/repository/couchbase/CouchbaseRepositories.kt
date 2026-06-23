package com.example.orderservice.repository.couchbase

import com.example.orderservice.entity.couchbase.OrderDocument
import com.example.orderservice.entity.couchbase.ProductDocument
import com.example.orderservice.entity.couchbase.UserDocument
import org.springframework.data.couchbase.repository.CouchbaseRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface OrderCouchbaseRepository : CouchbaseRepository<OrderDocument, UUID> {
    fun findByUserId(userId: UUID, pageable: Pageable): Page<OrderDocument>
}

interface UserCouchbaseRepository : CouchbaseRepository<UserDocument, UUID>

interface ProductCouchbaseRepository : CouchbaseRepository<ProductDocument, UUID>
