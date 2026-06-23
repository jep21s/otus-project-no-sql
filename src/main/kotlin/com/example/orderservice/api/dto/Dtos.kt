package com.example.orderservice.api.dto

import com.example.orderservice.model.OrderStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AddressDto(
    @field:NotBlank val street: String,
    @field:NotBlank val city: String,
    @field:NotBlank val state: String,
    @field:NotBlank val zipCode: String,
    @field:NotBlank val country: String
)

data class OrderItemDto(
    val id: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: BigDecimal
)

data class OrderDto(
    val id: UUID,
    val userId: UUID,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val shippingAddress: AddressDto,
    val items: List<OrderItemDto>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ProductDto(
    val id: UUID,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val stock: Int,
    val category: String,
    val createdAt: Instant
)

data class UserDto(
    val id: UUID,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String
)

data class CreateOrderItemRequest(
    @field:NotNull val productId: UUID,
    @field:Positive val quantity: Int
)

data class CreateOrderRequest(
    @field:NotEmpty val items: List<@Valid CreateOrderItemRequest>,
    @field:Valid val shippingAddress: AddressDto
)

data class UpdateOrderStatusRequest(
    @field:NotNull val status: OrderStatus
)

