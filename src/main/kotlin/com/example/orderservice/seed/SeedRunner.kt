package com.example.orderservice.seed

import com.example.orderservice.entity.couchbase.OrderDocument
import com.example.orderservice.entity.couchbase.ProductDocument
import com.example.orderservice.entity.couchbase.UserDocument
import com.example.orderservice.mapper.CouchbaseMapperImpl
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.boot.WebApplicationType
import org.springframework.data.couchbase.core.CouchbaseTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Сидирование БД через само приложение (Java SDK / JPA — гарантированно корректный формат).
 * Активируется env APP_SEED=true. Работает и для PG (JdbcTemplate), и для Couchbase (CouchbaseTemplate).
 * Запускается как одноразовый контейнер в сети compose, после заполнения — завершается.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("app.seed", havingValue = "true")
class SeedRunner(
    private val ctx: ApplicationContext,
    private val dataSourceProvider: org.springframework.beans.factory.ObjectProvider<DataSource>,
    private val couchbaseProvider: org.springframework.beans.factory.ObjectProvider<CouchbaseTemplate>
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val ds = dataSourceProvider.ifAvailable
        val cb = couchbaseProvider.ifAvailable
        when {
            ds != null -> {
                val jdbc = JdbcTemplate(ds)
                cleanPostgres(jdbc)
                seedPostgres(jdbc)
            }
            cb != null -> {
                cleanCouchbase(cb)
                seedCouchbase(cb)
            }
            else -> throw IllegalStateException("Ни DataSource, ни CouchbaseTemplate не найдены в контексте")
        }
        log.info("[seed] completed successfully")
        SpringApplication.exit(ctx)
    }

    private fun cleanPostgres(jdbc: JdbcTemplate) {
        jdbc.execute("TRUNCATE TABLE order_items, orders, products, users RESTART IDENTITY CASCADE")
        log.info("[seed] postgres truncated")
    }

    private fun seedPostgres(jdbc: JdbcTemplate) {
        val users = SeedDataset.users()
        jdbc.batchUpdate(
            "INSERT INTO users (id, username, email, first_name, last_name, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
            users, 1000
        ) { ps, u ->
            ps.setObject(1, u.id); ps.setString(2, u.username); ps.setString(3, u.email)
            ps.setString(4, u.firstName); ps.setString(5, u.lastName)
            ps.setTimestamp(6, Timestamp.from(u.createdAt)); ps.setTimestamp(7, Timestamp.from(u.updatedAt))
        }
        log.info("[seed] users=${users.size}")

        val products = SeedDataset.products()
        jdbc.batchUpdate(
            "INSERT INTO products (id, name, description, price, stock, category, created_at) VALUES (?,?,?,?,?,?,?)",
            products, 500
        ) { ps, p ->
            ps.setObject(1, p.id); ps.setString(2, p.name); ps.setString(3, p.description)
            ps.setBigDecimal(4, p.price); ps.setInt(5, p.stock); ps.setString(6, p.category)
            ps.setTimestamp(7, Timestamp.from(p.createdAt))
        }
        log.info("[seed] products=${products.size}")

        val orders = SeedDataset.orders()
        jdbc.batchUpdate(
            "INSERT INTO orders (id, user_id, status, total_amount, ship_street, ship_city, ship_state, ship_zip, ship_country, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            orders, 500
        ) { ps, o ->
            ps.setObject(1, o.id); ps.setObject(2, o.userId); ps.setString(3, o.status.name)
            ps.setBigDecimal(4, o.totalAmount); ps.setString(5, o.shippingAddress.street)
            ps.setString(6, o.shippingAddress.city); ps.setString(7, o.shippingAddress.state)
            ps.setString(8, o.shippingAddress.zipCode); ps.setString(9, o.shippingAddress.country)
            ps.setTimestamp(10, Timestamp.from(o.createdAt)); ps.setTimestamp(11, Timestamp.from(o.updatedAt))
        }
        log.info("[seed] orders=${orders.size}")

        val items = orders.flatMap { o -> o.items.map { it to o.id } }
        jdbc.batchUpdate(
            "INSERT INTO order_items (id, order_id, product_id, product_name, quantity, unit_price) VALUES (?,?,?,?,?,?)",
            items, 2000
        ) { ps, (it, oid) ->
            ps.setObject(1, it.id); ps.setObject(2, oid); ps.setObject(3, it.productId)
            ps.setString(4, it.productName); ps.setInt(5, it.quantity); ps.setBigDecimal(6, it.unitPrice)
        }
        log.info("[seed] order_items=${items.size}")
    }

    private fun cleanCouchbase(cb: CouchbaseTemplate) {
        val bucket = cb.couchbaseClientFactory.bucket.name()
        cb.couchbaseClientFactory.cluster.query("DELETE FROM `$bucket` WHERE META().id LIKE 'cache:%'")
        cb.couchbaseClientFactory.cluster.query("DELETE FROM `$bucket` WHERE `_class` IS NOT NULL")
        log.info("[seed] couchbase cleared")
    }

    private fun ensureCouchbaseIndexes(cb: CouchbaseTemplate) {
        val b = cb.couchbaseClientFactory.bucket.name()
        val stmts = listOf(
            "CREATE PRIMARY INDEX IF NOT EXISTS ON `$b`",
            "CREATE INDEX IF NOT EXISTS idx_class ON `$b`(`_class`)",
            "CREATE INDEX IF NOT EXISTS idx_order_uid ON `$b`(userId)",
            "CREATE INDEX IF NOT EXISTS idx_order_uid_created ON `$b`(userId, createdAt DESC)",
            "CREATE INDEX IF NOT EXISTS idx_cache_user ON `$b`(`cu`) WHERE `cu` IS NOT NULL"
        )
        for (sql in stmts) {
            try {
                cb.couchbaseClientFactory.cluster.query(sql)
            } catch (e: Exception) {
                log.warn("[seed] index stmt failed (ignored): {} -> {}", sql, e.message)
            }
        }
        log.info("[seed] couchbase indexes ensured")
    }

    private fun seedCouchbase(cb: CouchbaseTemplate) {
        ensureCouchbaseIndexes(cb)
        val users = SeedDataset.users()
        cb.insertById(UserDocument::class.java).all(users.map { CouchbaseMapperImpl.toDocument(it) })
        log.info("[seed] users=${users.size}")

        val products = SeedDataset.products()
        cb.insertById(ProductDocument::class.java).all(products.map { CouchbaseMapperImpl.toDocument(it) })
        log.info("[seed] products=${products.size}")

        val orders = SeedDataset.orders()
        val docs = orders.map { CouchbaseMapperImpl.toDocument(it) }
        docs.chunked(500).forEach { chunk ->
            cb.insertById(OrderDocument::class.java).all(chunk)
        }
        log.info("[seed] orders=${orders.size}")
    }
}
