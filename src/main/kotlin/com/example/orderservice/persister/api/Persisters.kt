package com.example.orderservice.persister.api

import com.example.orderservice.model.Order
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import java.util.UUID

interface OrderPersister {
    fun findById(id: UUID): Order?
    fun findByUserId(userId: UUID, limit: Int, offset: Int): List<Order>
    fun save(order: Order): Order
    fun deleteById(id: UUID): Boolean
    fun existsById(id: UUID): Boolean
}

interface UserPersister {
    fun findById(id: UUID): User?
    fun save(user: User): User
}

interface ProductPersister {
    fun findById(id: UUID): Product?
    fun findAll(limit: Int, offset: Int): List<Product>
    fun findAllById(ids: Collection<UUID>): List<Product>
    fun save(product: Product): Product
    fun saveAll(products: List<Product>): List<Product>
}
