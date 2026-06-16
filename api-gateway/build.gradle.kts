plugins {
    alias(libs.plugins.spring.boot)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.bom.get().toString())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation(libs.spring.boot.starter.actuator)
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.spring.kafka)
    implementation(libs.caffeine)
    implementation(libs.springdoc.openapi.webflux.ui)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // QuickFIX/J — inbound FIX 4.4 acceptor for programmatic order entry (roadmap 3.4.3).
    implementation(libs.quickfixj.core)
    implementation(libs.quickfixj.messages.fix44)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.mockwebserver)
}