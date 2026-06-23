package com.example.orderservice.service

import com.example.orderservice.model.User
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.persister.api.UserPersister
import com.example.orderservice.service.exception.NotFoundException
import com.example.orderservice.web.RequestContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService(private val userPersister: UserPersister) {

    fun getCurrentUser(): User =
        userPersister.findById(RequestContext.getUserId())
            ?: throw NotFoundException("Current user not found")
}

@Service
class ProductService(private val productPersister: ProductPersister) {

    fun getProduct(id: UUID): com.example.orderservice.model.Product =
        productPersister.findById(id)
            ?: throw NotFoundException("Product $id not found")

    fun getProducts(limit: Int, offset: Int): List<com.example.orderservice.model.Product> {
        require(limit in 1..200) { "limit must be 1..200" }
        require(offset >= 0) { "offset must be >= 0" }
        return productPersister.findAll(limit, offset)
    }
}
