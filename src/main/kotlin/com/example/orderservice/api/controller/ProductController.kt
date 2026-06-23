package com.example.orderservice.api.controller

import com.example.orderservice.mapper.DtoMapperImpl
import com.example.orderservice.service.ProductService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/products")
class ProductController(private val productService: ProductService) {

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ) = productService.getProducts(limit, offset).map { DtoMapperImpl.toDto(it) }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) = DtoMapperImpl.toDto(productService.getProduct(id))
}
