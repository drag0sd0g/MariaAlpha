plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Spring Cloud Gateway requires the Spring Cloud BOM — configured in ticket 1.8.1
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}