package com.example.orderservice.entity.couchbase

import com.example.orderservice.model.Address
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.OrderStatus
import org.springframework.data.annotation.Id
import org.springframework.data.couchbase.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Document
data class UserDocument(
    @Id val id: UUID,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Document
data class ProductDocument(
    @Id val id: UUID,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val stock: Int,
    val category: String,
    val createdAt: Instant
)

@Document
data class OrderDocument(
    @Id val id: UUID,
    val userId: UUID,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val shippingAddress: Address,
    val items: List<OrderItem>,
    val createdAt: Instant,
    val updatedAt: Instant
)
