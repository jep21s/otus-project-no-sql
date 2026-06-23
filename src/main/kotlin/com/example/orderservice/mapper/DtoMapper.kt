package com.example.orderservice.mapper

import com.example.orderservice.api.dto.AddressDto
import com.example.orderservice.api.dto.OrderDto
import com.example.orderservice.api.dto.OrderItemDto
import com.example.orderservice.api.dto.ProductDto
import com.example.orderservice.api.dto.UserDto
import com.example.orderservice.model.Address
import com.example.orderservice.model.Order
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import io.mcarle.konvert.api.Konverter

@Konverter
interface DtoMapper {
    fun toDto(order: Order): OrderDto
    fun toDto(item: OrderItem): OrderItemDto
    fun toDto(address: Address): AddressDto
    fun toDto(user: User): UserDto
    fun toDto(product: Product): ProductDto
}
