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

# Clean build (auto-fixes formatting first)
build:
    just fix
    ./gradlew clean build

# Run all tests (Java + Python)
test:
    just test-java
    just test-python

# Run Java unit and integration tests
test-java:
    ./gradlew test

# Run integration tests only (requires credentials)
test-integration:
    ./gradlew test -PincludeTags=integration

# Run Python tests
test-python:
    @echo "Python test runner not yet configured"

# Run all linters and format checks (CI gate)
check:
    ./gradlew spotlessCheck checkstyleMain spotbugsMain
    ruff check .
    ruff format --check .

# Auto-fix all formatting and lint violations
fix:
    ./gradlew spotlessApply
    ruff check --fix .
    ruff format .

# Build all Docker images
docker-build:
    @echo "Dockerfiles not yet created"

# Generate gRPC stubs from proto definitions (Java + Python)
proto:
    ./gradlew :proto:generateProto
    bash proto/generate_python.sh

# Run database migrations (auto-run on each service startup)
migrate:
    @echo "Migrations auto-run on service startup."
    @echo "Verify: docker compose exec postgres psql -U mariaalpha -c '\\dt'"
