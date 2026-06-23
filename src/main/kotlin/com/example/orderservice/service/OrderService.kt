package com.example.orderservice.service

import com.example.orderservice.api.dto.CreateOrderRequest
import com.example.orderservice.model.Address
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.persister.api.OrderPersister
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.service.exception.ForbiddenException
import com.example.orderservice.service.exception.NotFoundException
import com.example.orderservice.web.RequestContext
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class OrderService(
    private val orderPersister: OrderPersister,
    private val productPersister: ProductPersister
) {

    fun createOrder(request: CreateOrderRequest): Order {
        require(request.items.isNotEmpty()) { "Order must contain at least one item" }

        val userId = RequestContext.getUserId()
        val productIds = request.items.map { it.productId }.toSet()
        val products = productPersister.findAllById(productIds).associateBy { it.id }

        val now = Instant.now()
        val items = request.items.mapIndexed { index, req ->
            val product = products[req.productId]
                ?: throw NotFoundException("Product ${req.productId} not found")
            require(req.quantity > 0) { "Quantity must be positive" }
            require(product.stock >= req.quantity) { "Insufficient stock for ${product.name}" }
            OrderItem(
                id = UUID.randomUUID(),
                productId = product.id,
                productName = product.name,
                quantity = req.quantity,
                unitPrice = product.price
            )
        }

        val total = items.fold(BigDecimal.ZERO) { acc, i -> acc + i.unitPrice.multiply(BigDecimal(i.quantity)) }
        val order = Order(
            id = UUID.randomUUID(),
            userId = userId,
            status = OrderStatus.CREATED,
            totalAmount = total,
            shippingAddress = Address(
                street = request.shippingAddress.street,
                city = request.shippingAddress.city,
                state = request.shippingAddress.state,
                zipCode = request.shippingAddress.zipCode,
                country = request.shippingAddress.country
            ),
            items = items,
            createdAt = now,
            updatedAt = now
        )
        return orderPersister.save(order)
    }

    fun getOrder(id: UUID): Order {
        val order = orderPersister.findById(id) ?: throw NotFoundException("Order $id not found")
        ensureOwnedByCurrentUser(order)
        return order
    }

    fun getMyOrders(limit: Int, offset: Int): List<Order> {
        require(limit in 1..200) { "limit must be 1..200" }
        require(offset >= 0) { "offset must be >= 0" }
        return orderPersister.findByUserId(RequestContext.getUserId(), limit, offset)
    }

    fun updateStatus(id: UUID, newStatus: OrderStatus): Order {
        val order = getOrder(id)
        val updated = order.copy(status = newStatus, updatedAt = Instant.now())
        return orderPersister.save(updated)
    }

    fun deleteOrder(id: UUID) {
        val order = getOrder(id)
        orderPersister.deleteById(order.id)
    }

    private fun ensureOwnedByCurrentUser(order: Order) {
        if (order.userId != RequestContext.getUserId()) {
            throw ForbiddenException("Order does not belong to the current user")
        }
    }
}
