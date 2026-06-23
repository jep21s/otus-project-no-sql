package com.example.orderservice.model

import java.util.UUID

data class Address(
    val street: String,
    val city: String,
    val state: String,
    val zipCode: String,
    val country: String
)

data class OrderItem(
    val id: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: java.math.BigDecimal
)

enum class OrderStatus { CREATED, PAID, SHIPPED, DELIVERED, CANCELLED }

data class Order(
    val id: UUID,
    val userId: UUID,
    val status: OrderStatus,
    val totalAmount: java.math.BigDecimal,
    val shippingAddress: Address,
    val items: List<OrderItem>,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant
)

data class User(
    val id: UUID,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant
)

data class Product(
    val id: UUID,
    val name: String,
    val description: String,
    val price: java.math.BigDecimal,
    val stock: Int,
    val category: String,
    val createdAt: java.time.Instant
)
