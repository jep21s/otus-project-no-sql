plugins {
    val kotlinVersion = "2.0.21"
    val springBootVersion = "3.3.6"
    val dependencyManagementVersion = "1.1.6"
    val kspVersion = "2.0.21-1.0.28"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("plugin.jpa") version kotlinVersion
    id("org.springframework.boot") version springBootVersion
    id("io.spring.dependency-management") version dependencyManagementVersion
    id("com.google.devtools.ksp") version kspVersion
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

// Override Kotlin version managed by Spring Boot BOM (BOM ships 1.9.25) to match konvert/KSP
extra["kotlin.version"] = "2.0.21"
extra["konvert.version"] = "4.3.2"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // PostgreSQL + JPA + Liquibase
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.apache.commons:commons-pool2")

    // Couchbase
    implementation("org.springframework.boot:spring-boot-starter-data-couchbase")

    // konvert (KSP-based mapper)
    implementation("io.mcarle:konvert-api:${property("konvert.version")}")
    ksp("io.mcarle:konvert:${property("konvert.version")}")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.springframework.amqp:spring-rabbit-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:couchbase")
    testImplementation("org.testcontainers:testcontainers")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    archiveFileName.set("order-service.jar")
}
