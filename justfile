# List all available recipes
default:
    @just --list

# Start all infrastructure services
run:
    docker compose up -d

# Stop all infrastructure services
stop:
    docker compose down

# Clean all build artefacts
clean:
    ./gradlew clean

# Run all tests (Java + Python)
test:
    just test-java
    just test-python

# Run Java unit and integration tests
test-java:
    ./gradlew test

# Run Python tests
test-python:
    @echo "Python test runner not yet configured"

# Run all linters
lint:
    ./gradlew checkstyleMain spotbugsMain
    ruff check .

# Build all Docker images
docker-build:
    @echo "Dockerfiles not yet created"

# Generate gRPC stubs from proto definitions
proto:
    @echo "gRPC codegen not yet configured"

# Run database migrations
migrate:
    @echo "Liquibase migrations not yet configured"
