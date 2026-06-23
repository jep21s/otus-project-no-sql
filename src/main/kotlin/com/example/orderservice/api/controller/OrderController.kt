package com.example.orderservice.api.controller

import com.example.orderservice.api.dto.CreateOrderRequest
import com.example.orderservice.api.dto.UpdateOrderStatusRequest
import com.example.orderservice.mapper.DtoMapperImpl
import com.example.orderservice.service.OrderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateOrderRequest) =
        DtoMapperImpl.toDto(orderService.createOrder(request))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) =
        DtoMapperImpl.toDto(orderService.getOrder(id))

    @GetMapping
    fun myOrders(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ) = orderService.getMyOrders(limit, offset).map { DtoMapperImpl.toDto(it) }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateOrderStatusRequest
    ) = DtoMapperImpl.toDto(orderService.updateStatus(id, request.status))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        orderService.deleteOrder(id)
    }
}
