plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Resilience4j
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.timelimiter)

    // OkHttp for Alpaca WebSocket
    implementation(libs.okhttp)

    // Guava (rate limiter fallback)
    implementation(libs.guava)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
}