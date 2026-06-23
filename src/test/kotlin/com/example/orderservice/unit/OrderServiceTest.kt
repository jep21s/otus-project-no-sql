package com.example.orderservice.unit

import com.example.orderservice.api.dto.AddressDto
import com.example.orderservice.api.dto.CreateOrderItemRequest
import com.example.orderservice.api.dto.CreateOrderRequest
import com.example.orderservice.mapper.DtoMapperImpl
import com.example.orderservice.model.Address
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import com.example.orderservice.persister.api.OrderPersister
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.service.OrderService
import com.example.orderservice.service.exception.ForbiddenException
import com.example.orderservice.service.exception.NotFoundException
import com.example.orderservice.web.RequestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OrderServiceTest {

    private val orderPersister = mockk<OrderPersister>()
    private val productPersister = mockk<ProductPersister>()
    private val service = OrderService(orderPersister, productPersister)

    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2025-01-01T10:00:00Z")

    @BeforeEach
    fun setupContext() {
        RequestContext.setUserId(userId)
    }

    @AfterEach
    fun clearContext() {
        RequestContext.clear()
    }

    private fun product(id: UUID, price: BigDecimal, stock: Int) =
        Product(id, "prod-$id", "desc", price, stock, "cat", now)

    private fun sampleRequest(productId: UUID, qty: Int) = CreateOrderRequest(
        items = listOf(CreateOrderItemRequest(productId, qty)),
        shippingAddress = AddressDto("street", "city", "state", "zip", "country")
    )

    @Test
    fun `create order computes total and persists`() {
        val pid = UUID.randomUUID()
        every { productPersister.findAllById(setOf(pid)) } returns listOf(product(pid, BigDecimal("10.50"), 100))
        every { orderPersister.save(any()) } returnsArgument 0

        val order = service.createOrder(sampleRequest(pid, 3))

        assertEquals(BigDecimal("31.50"), order.totalAmount)
        assertEquals(OrderStatus.CREATED, order.status)
        assertEquals(1, order.items.size)
        assertEquals("prod-$pid", order.items[0].productName)
        verify { orderPersister.save(any()) }
    }

    @Test
    fun `create order rejects insufficient stock`() {
        val pid = UUID.randomUUID()
        every { productPersister.findAllById(setOf(pid)) } returns listOf(product(pid, BigDecimal.ONE, 1))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.createOrder(sampleRequest(pid, 5))
        }
        assertTrue(ex.message!!.contains("stock", ignoreCase = true))
    }

    @Test
    fun `create order rejects unknown product`() {
        val pid = UUID.randomUUID()
        every { productPersister.findAllById(any()) } returns emptyList()

        assertThrows(NotFoundException::class.java) {
            service.createOrder(sampleRequest(pid, 1))
        }
    }

    @Test
    fun `getOrder forbids foreign order`() {
        val foreignOrder = Order(
            UUID.randomUUID(), UUID.randomUUID(), OrderStatus.CREATED, BigDecimal.TEN,
            Address("s", "c", "st", "z", "co"),
            emptyList(), now, now
        )
        every { orderPersister.findById(foreignOrder.id) } returns foreignOrder

        assertThrows(ForbiddenException::class.java) { service.getOrder(foreignOrder.id) }
    }

    @Test
    fun `getMyOrders delegates to persister`() {
        every { orderPersister.findByUserId(userId, 20, 0) } returns emptyList()
        assertTrue(service.getMyOrders(20, 0).isEmpty())
    }

    @Test
    fun `updateStatus saves updated order`() {
        val order = Order(
            UUID.randomUUID(), userId, OrderStatus.CREATED, BigDecimal.TEN,
            Address("s", "c", "st", "z", "co"), emptyList(), now, now
        )
        every { orderPersister.findById(order.id) } returns order
        every { orderPersister.save(any()) } returnsArgument 0

        val updated = service.updateStatus(order.id, OrderStatus.PAID)
        assertEquals(OrderStatus.PAID, updated.status)
    }

    @Test
    fun `dto mapper round-trips order`() {
        val item = OrderItem(UUID.randomUUID(), UUID.randomUUID(), "name", 2, BigDecimal("3.00"))
        val order = Order(
            UUID.randomUUID(), userId, OrderStatus.PAID, BigDecimal("6.00"),
            Address("s", "c", "st", "z", "co"), listOf(item), now, now
        )
        val dto = DtoMapperImpl.toDto(order)
        assertEquals(order.id, dto.id)
        assertEquals(1, dto.items.size)
        assertEquals("s", dto.shippingAddress.street)

        val user = User(userId, "u", "e", "f", "l", now, now)
        assertEquals("u", DtoMapperImpl.toDto(user).username)
    }
}
