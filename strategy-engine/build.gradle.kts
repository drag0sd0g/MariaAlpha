plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.javax.annotation.api)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.guava)
    implementation(project(":proto"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.grpc.inprocess)
}