#!/bin/bash
# Run functional tests with Docker environment and gateway

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GATEWAY_PID=""

cleanup() {
    echo ""
    echo "Cleaning up..."

    if [ -n "$GATEWAY_PID" ] && kill -0 "$GATEWAY_PID" 2>/dev/null; then
        echo "Stopping gateway (PID: $GATEWAY_PID)..."
        kill "$GATEWAY_PID" 2>/dev/null || true
        wait "$GATEWAY_PID" 2>/dev/null || true
    fi

    echo "Stopping Docker environment..."
    ./docker/stop-test-env.sh
}

trap cleanup EXIT

echo "=== Starting Functional Tests ==="
echo ""

# Step 1: Start Docker environment
echo "Step 1: Starting Docker environment..."
cd docker
docker-compose up -d
cd ..

# Wait for Consul to be ready
echo "Waiting for Consul..."
for i in {1..30}; do
    if curl -s http://localhost:8500/v1/status/leader | grep -q .; then
        echo "Consul is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: Consul failed to start"
        exit 1
    fi
    sleep 1
done

# Wait for services to register
echo "Waiting for services to register..."
sleep 15

# Step 2: Start the gateway in background
echo ""
echo "Step 2: Starting gateway..."
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
export ZUUL_REACHABLE_ENVIRONMENTS=dev:test

./gradlew :app:run --quiet &
GATEWAY_PID=$!

# Wait for gateway to be ready
echo "Waiting for gateway to start..."
for i in {1..30}; do
    if curl -s http://localhost:9091/ | grep -q "zuul-consul"; then
        echo "Gateway is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: Gateway failed to start"
        exit 1
    fi
    sleep 1
done

# Step 3: Run functional tests
echo ""
echo "Step 3: Running functional tests..."
echo ""

./gradlew :app:functionalTest
TEST_RESULT=$?

echo ""
if [ $TEST_RESULT -eq 0 ]; then
    echo "=== Functional Tests PASSED ==="
else
    echo "=== Functional Tests FAILED ==="
fi

exit $TEST_RESULT
