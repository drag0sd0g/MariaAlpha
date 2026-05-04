FROM gradle:8.12-jdk21 AS builder
WORKDIR /workspace

# Root build files
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./

# Stub sibling modules so Gradle multi-project config succeeds
COPY market-data-gateway/build.gradle.kts market-data-gateway/
COPY strategy-engine/build.gradle.kts strategy-engine/
COPY execution-engine/build.gradle.kts execution-engine/
COPY order-manager/build.gradle.kts order-manager/
COPY post-trade/build.gradle.kts post-trade/
COPY proto/build.gradle.kts proto/
COPY e2e-tests/build.gradle.kts e2e-tests/

# api-gateway sources
COPY api-gateway/build.gradle.kts api-gateway/
COPY api-gateway/src api-gateway/src

RUN ./gradlew :api-gateway:bootJar --no-daemon -x test -x checkstyleMain -x spotbugsMain -x spotlessCheck

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /workspace/api-gateway/build/libs/api-gateway.jar app.jar
EXPOSE 8080 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
