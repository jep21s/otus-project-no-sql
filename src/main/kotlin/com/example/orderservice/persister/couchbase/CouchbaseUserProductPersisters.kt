package com.example.orderservice.persister.couchbase

import com.example.orderservice.mapper.CouchbaseMapperImpl
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.persister.api.UserPersister
import com.example.orderservice.repository.couchbase.ProductCouchbaseRepository
import com.example.orderservice.repository.couchbase.UserCouchbaseRepository
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@Profile("a2", "b2", "c2")
class CouchbaseUserPersister(
    private val userRepository: UserCouchbaseRepository
) : UserPersister {

    override fun findById(id: UUID): User? =
        userRepository.findById(id).orElse(null)?.let { CouchbaseMapperImpl.toModel(it) }

    override fun save(user: User): User =
        CouchbaseMapperImpl.toModel(userRepository.save(CouchbaseMapperImpl.toDocument(user)))
}

@Component
@Profile("a2", "b2", "c2")
class CouchbaseProductPersister(
    private val productRepository: ProductCouchbaseRepository
) : ProductPersister {

    override fun findById(id: UUID): Product? =
        productRepository.findById(id).orElse(null)?.let { CouchbaseMapperImpl.toModel(it) }

    override fun findAll(limit: Int, offset: Int): List<Product> {
        val page = PageRequest.of(offset / limit.coerceAtLeast(1), limit)
        return productRepository.findAll(page).content.map { CouchbaseMapperImpl.toModel(it) }
    }

    override fun findAllById(ids: Collection<UUID>): List<Product> =
        productRepository.findAllById(ids).map { CouchbaseMapperImpl.toModel(it) }.toList()

    override fun save(product: Product): Product =
        CouchbaseMapperImpl.toModel(productRepository.save(CouchbaseMapperImpl.toDocument(product)))

    override fun saveAll(products: List<Product>): List<Product> =
        productRepository.saveAll(products.map { CouchbaseMapperImpl.toDocument(it) })
            .map { CouchbaseMapperImpl.toModel(it) }
}
