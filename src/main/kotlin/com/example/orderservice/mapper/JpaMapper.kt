package com.example.orderservice.mapper

import com.example.orderservice.entity.jpa.AddressEmbeddable
import com.example.orderservice.entity.jpa.OrderItemEntity
import com.example.orderservice.entity.jpa.ProductEntity
import com.example.orderservice.entity.jpa.UserEntity
import com.example.orderservice.model.Address
import com.example.orderservice.model.OrderItem
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import io.mcarle.konvert.api.Konverter

@Konverter
interface JpaMapper {
    fun toEntity(user: User): UserEntity
    fun toModel(entity: UserEntity): User

    fun toEntity(product: Product): ProductEntity
    fun toModel(entity: ProductEntity): Product

    fun toEntity(item: OrderItem): OrderItemEntity
    fun toModel(entity: OrderItemEntity): OrderItem

    fun toEmbeddable(address: Address): AddressEmbeddable
    fun toModel(address: AddressEmbeddable): Address
}
