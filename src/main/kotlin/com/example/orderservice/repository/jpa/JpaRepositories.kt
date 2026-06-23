package com.example.orderservice.repository.jpa

import com.example.orderservice.entity.jpa.OrderEntity
import com.example.orderservice.entity.jpa.ProductEntity
import com.example.orderservice.entity.jpa.UserEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    @Query("select distinct o from OrderEntity o left join fetch o.items where o.userId = :userId order by o.createdAt desc")
    fun findByUserId(userId: UUID, pageable: Pageable): List<OrderEntity>
}

interface UserJpaRepository : JpaRepository<UserEntity, UUID>

interface ProductJpaRepository : JpaRepository<ProductEntity, UUID> {
    fun findAllByIdInOrderById(ids: Collection<UUID>): List<ProductEntity>
}
