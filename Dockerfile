# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# Кэшируемый слой зависимостей
COPY gradle/ ./gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew

# Исходники и сборка fat-jar
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /build/build/libs/order-service.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
