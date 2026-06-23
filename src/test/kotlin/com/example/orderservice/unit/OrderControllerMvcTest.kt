package com.example.orderservice.unit

import com.example.orderservice.api.dto.AddressDto
import com.example.orderservice.api.dto.CreateOrderItemRequest
import com.example.orderservice.api.dto.CreateOrderRequest
import com.example.orderservice.api.dto.OrderDto
import com.example.orderservice.api.dto.OrderItemDto
import com.example.orderservice.api.controller.OrderController
import com.example.orderservice.model.OrderStatus
import com.example.orderservice.service.OrderService
import com.example.orderservice.web.GlobalExceptionHandler
import com.example.orderservice.web.UserIdFilter
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [OrderController::class])
@Import(UserIdFilter::class, GlobalExceptionHandler::class)
class OrderControllerMvcTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @MockkBean private lateinit var orderService: OrderService

    @Test
    fun `requests without X-User-Id are rejected with 401`() {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `my orders returns 200 with header`() {
        val order = com.example.orderservice.model.Order(
            UUID.randomUUID(), UUID.randomUUID(), OrderStatus.CREATED, BigDecimal.TEN,
            com.example.orderservice.model.Address("s", "c", "st", "z", "co"),
            listOf(com.example.orderservice.model.OrderItem(UUID.randomUUID(), UUID.randomUUID(), "p", 1, BigDecimal.TEN)),
            Instant.EPOCH, Instant.EPOCH
        )
        every { orderService.getMyOrders(any(), any()) } returns listOf(order)

        mockMvc.perform(get("/api/orders").header("X-User-Id", UUID.randomUUID()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].totalAmount").value(10))
    }

    @Test
    fun `create order with empty items is rejected`() {
        val body = objectMapper.writeValueAsString(
            CreateOrderRequest(emptyList(), AddressDto("s", "c", "st", "z", "co"))
        )
        mockMvc.perform(
            post("/api/orders").header("X-User-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `create order returns 201 when valid`() {
        val order = com.example.orderservice.model.Order(
            UUID.randomUUID(), UUID.randomUUID(), OrderStatus.CREATED, BigDecimal.TEN,
            com.example.orderservice.model.Address("s", "c", "st", "z", "co"),
            listOf(com.example.orderservice.model.OrderItem(UUID.randomUUID(), UUID.randomUUID(), "p", 2, BigDecimal("5.00"))),
            Instant.EPOCH, Instant.EPOCH
        )
        every { orderService.createOrder(any()) } returns order
        val body = objectMapper.writeValueAsString(
            CreateOrderRequest(
                listOf(CreateOrderItemRequest(UUID.randomUUID(), 2)),
                AddressDto("s", "c", "st", "z", "co")
            )
        )
        mockMvc.perform(
            post("/api/orders").header("X-User-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
    }
}
