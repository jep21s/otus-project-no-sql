package com.example.orderservice.entity.jpa

import com.example.orderservice.model.OrderStatus
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Embeddable
data class AddressEmbeddable(
    @Column(name = "ship_street", nullable = false) var street: String = "",
    @Column(name = "ship_city", nullable = false) var city: String = "",
    @Column(name = "ship_state", nullable = false) var state: String = "",
    @Column(name = "ship_zip", nullable = false) var zipCode: String = "",
    @Column(name = "ship_country", nullable = false) var country: String = ""
)

@Entity
@Table(name = "users")
class UserEntity(
    @Id @Column(name = "id") var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) var username: String = "",
    @Column(nullable = false, unique = true) var email: String = "",
    @Column(name = "first_name", nullable = false) var firstName: String = "",
    @Column(name = "last_name", nullable = false) var lastName: String = "",
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH
)

@Entity
@Table(name = "products")
class ProductEntity(
    @Id @Column(name = "id") var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var name: String = "",
    @Column(nullable = false) var description: String = "",
    @Column(nullable = false, precision = 19, scale = 2) var price: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var stock: Int = 0,
    @Column(nullable = false) var category: String = "",
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
)

@Entity
@Table(name = "order_items")
class OrderItemEntity(
    @Id @Column(name = "id") var id: UUID = UUID.randomUUID(),
    @Column(name = "product_id", nullable = false) var productId: UUID = UUID.randomUUID(),
    @Column(name = "product_name", nullable = false) var productName: String = "",
    @Column(nullable = false) var quantity: Int = 0,
    @Column(nullable = false, precision = 19, scale = 2) var unitPrice: BigDecimal = BigDecimal.ZERO
)

@Entity
@Table(name = "orders")
class OrderEntity(
    @Id @Column(name = "id") var id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) var userId: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: OrderStatus = OrderStatus.CREATED,
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2) var totalAmount: BigDecimal = BigDecimal.ZERO,
    @Embedded var shippingAddress: AddressEmbeddable = AddressEmbeddable(),
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    var items: MutableList<OrderItemEntity> = mutableListOf(),
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH
)
