set dotenv-load

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

# Build all Docker images (UI + every Java/Python service)
docker-build:
    docker compose build

# Bring up the full stack and verify each service is healthy
verify:
    #!/usr/bin/env bash
    echo "Polling /actuator/health on every service..."
    check() {
        local name=$1 url=$2
        if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
            echo "  ✓ $name"
        else
            echo "  ✗ $name ($url)"
        fi
    }
    check market-data-gateway  http://localhost:8081/actuator/health/liveness
    check strategy-engine       http://localhost:8083/actuator/health/liveness
    check execution-engine      http://localhost:8085/actuator/health/liveness
    check order-manager         http://localhost:8087/actuator/health/liveness
    check post-trade            http://localhost:8089/actuator/health/liveness
    check api-gateway           http://localhost:8091/actuator/health/liveness
    check ml-signal-service     http://localhost:8090/health
    check ui                    http://localhost:5173/
    check grafana               http://localhost:3001/api/health

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

# End-to-end smoke test against Alpaca paper trading (manual; requires real Alpaca creds in .env)
smoke-alpaca:
    @if [ -z "${ALPACA_API_KEY_ID:-}" ]; then echo "Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY in .env first"; exit 1; fi
    MARKET_DATA_PROFILE=alpaca EXECUTION_PROFILE=alpaca docker compose up -d --build
    @echo ""
    @echo "Stack is starting. Wait ~90 s, then follow docs/runbooks/alpaca-smoke-test.md"
