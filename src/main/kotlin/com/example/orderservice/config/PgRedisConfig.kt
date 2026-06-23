package com.example.orderservice.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Активна на PG+Redis профилях (a1, b1, c1). Сканирует JPA-сущности и репозитории.
 * Couchbase-автоконфигурация отключается в application-<profile>.yaml.
 */
@Configuration
@Profile("a1 | b1 | c1")
@EntityScan(basePackages = ["com.example.orderservice.entity.jpa"])
@EnableJpaRepositories(basePackages = ["com.example.orderservice.repository.jpa"])
class PgRedisConfig
