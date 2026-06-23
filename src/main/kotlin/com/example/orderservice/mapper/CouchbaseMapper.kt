package com.example.orderservice.mapper

import com.example.orderservice.entity.couchbase.OrderDocument
import com.example.orderservice.entity.couchbase.ProductDocument
import com.example.orderservice.entity.couchbase.UserDocument
import com.example.orderservice.model.Order
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import io.mcarle.konvert.api.Konverter

@Konverter
interface CouchbaseMapper {
    fun toDocument(user: User): UserDocument
    fun toModel(document: UserDocument): User

    fun toDocument(product: Product): ProductDocument
    fun toModel(document: ProductDocument): Product

    fun toDocument(order: Order): OrderDocument
    fun toModel(document: OrderDocument): Order
}
