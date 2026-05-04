FROM gradle:8.12-jdk21 AS builder
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY market-data-gateway/build.gradle.kts market-data-gateway/
COPY execution-engine/build.gradle.kts execution-engine/
COPY order-manager/build.gradle.kts order-manager/
COPY post-trade/build.gradle.kts post-trade/
COPY api-gateway/build.gradle.kts api-gateway/
COPY proto/build.gradle.kts proto/build.gradle.kts
COPY proto/src proto/src
COPY e2e-tests/build.gradle.kts e2e-tests/
COPY strategy-engine/build.gradle.kts strategy-engine/
COPY strategy-engine/src strategy-engine/src
RUN ./gradlew :strategy-engine:bootJar --no-daemon -x test -x checkstyleMain -x spotbugsMain -x spotlessCheck

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /workspace/strategy-engine/build/libs/strategy-engine.jar app.jar
EXPOSE 8082 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
