FROM gradle:8.12-jdk21 AS builder
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY market-data-gateway/build.gradle.kts market-data-gateway/
COPY strategy-engine/build.gradle.kts strategy-engine/
COPY order-manager/build.gradle.kts order-manager/
COPY post-trade/build.gradle.kts post-trade/
COPY api-gateway/build.gradle.kts api-gateway/
COPY proto/build.gradle.kts proto/
COPY execution-engine/build.gradle.kts execution-engine/
COPY execution-engine/src execution-engine/src
RUN ./gradlew :execution-engine:bootJar --no-daemon -x test -x checkstyleMain -x spotbugsMain -x spotlessCheck

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /workspace/execution-engine/build/libs/execution-engine.jar app.jar
EXPOSE 8084 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
