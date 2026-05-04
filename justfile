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

# Clean build (auto-fixes formatting first, builds UI and Java)
build:
    just fix
    just ui-install
    just ui-lint
    just ui-test
    just ui-build
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

# Run end-to-end test (boots full docker-compose stack — slow, ~3-5 minutes)
test-e2e:
    ./gradlew :e2e-tests:test -PincludeTags=e2e --info

# Run Python tests
test-python:
    cd ml-signal-service && pytest

# Run all linters and format checks (CI gate)
check:
    ./gradlew spotlessCheck checkstyleMain spotbugsMain
    ruff check .
    ruff format --check .
    cd ml-signal-service && ruff check src/ tests/ && mypy src/

# Auto-fix all formatting and lint violations
fix:
    ./gradlew spotlessApply
    ruff check --fix .
    ruff format .
    cd ml-signal-service && ruff check --fix src/ tests/ && ruff format src/ tests/

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

# UI: install dependencies (run once after cloning or when package.json changes)
ui-install:
    cd ui && npm install

# UI: start dev server with proxy to api-gateway on 8080
ui-dev:
    cd ui && npm run dev

# UI: production-style build
ui-build:
    cd ui && npm run build

# UI: run unit tests
ui-test:
    cd ui && npm test

# UI: lint + format check
ui-lint:
    cd ui && npm run lint && npm run format:check

# UI: auto-fix formatting and lint violations
ui-fix:
    cd ui && npm run lint:fix && npm run format
