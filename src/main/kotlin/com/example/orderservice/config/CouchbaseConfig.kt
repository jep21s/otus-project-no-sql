package com.example.orderservice.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.couchbase.repository.config.EnableCouchbaseRepositories

/**
 * Активна на Couchbase-профилях (a2, b2, c2). Сканирует Couchbase-репозитории.
 * JPA/DataSource/Redis-автоконфигурация отключается в application-<profile>.yaml.
 */
@Configuration
@Profile("a2 | b2 | c2")
@EnableCouchbaseRepositories(basePackages = ["com.example.orderservice.repository.couchbase"])
class CouchbaseConfig
