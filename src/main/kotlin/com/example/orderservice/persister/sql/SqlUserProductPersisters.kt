package com.example.orderservice.persister.sql

import com.example.orderservice.mapper.JpaMapperImpl
import com.example.orderservice.model.Product
import com.example.orderservice.model.User
import com.example.orderservice.persister.api.ProductPersister
import com.example.orderservice.persister.api.UserPersister
import com.example.orderservice.repository.jpa.ProductJpaRepository
import com.example.orderservice.repository.jpa.UserJpaRepository
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Profile("a1", "b1", "c1")
@Transactional
class SqlUserPersister(
    private val userRepository: UserJpaRepository,
    private val entityManager: EntityManager
) : UserPersister {

    @Transactional(readOnly = true)
    override fun findById(id: UUID): User? =
        userRepository.findById(id).orElse(null)?.let { JpaMapperImpl.toModel(it) }

    override fun save(user: User): User =
        JpaMapperImpl.toModel(entityManager.merge(JpaMapperImpl.toEntity(user)).also { entityManager.flush() })
}

@Component
@Profile("a1", "b1", "c1")
@Transactional
class SqlProductPersister(
    private val productRepository: ProductJpaRepository,
    private val entityManager: EntityManager
) : ProductPersister {

    @Transactional(readOnly = true)
    override fun findById(id: UUID): Product? =
        productRepository.findById(id).orElse(null)?.let { JpaMapperImpl.toModel(it) }

    @Transactional(readOnly = true)
    override fun findAll(limit: Int, offset: Int): List<Product> {
        val page = PageRequest.of(offset / limit.coerceAtLeast(1), limit)
        return productRepository.findAll(page).map { JpaMapperImpl.toModel(it) }.toList()
    }

    @Transactional(readOnly = true)
    override fun findAllById(ids: Collection<UUID>): List<Product> =
        productRepository.findAllByIdInOrderById(ids).map { JpaMapperImpl.toModel(it) }

    override fun save(product: Product): Product =
        JpaMapperImpl.toModel(entityManager.merge(JpaMapperImpl.toEntity(product)).also { entityManager.flush() })

    override fun saveAll(products: List<Product>): List<Product> =
        products.map { JpaMapperImpl.toModel(entityManager.merge(JpaMapperImpl.toEntity(it))) }
            .also { entityManager.flush() }
}
