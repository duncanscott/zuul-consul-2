#!/bin/bash
# Start the Docker Compose test environment

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Starting Zuul Consul test environment..."

# Start Docker Compose
docker-compose up -d

# Wait for services to be healthy
echo "Waiting for services to become healthy..."
sleep 5

# Check Consul
echo "Checking Consul..."
for i in {1..30}; do
    if curl -s http://localhost:8500/v1/status/leader | grep -q .; then
        echo "Consul is ready!"
        break
    fi
    echo "Waiting for Consul... ($i/30)"
    sleep 1
done

# Wait for registrar to complete
echo "Waiting for service registration..."
sleep 10

# Show registered services
echo ""
echo "Registered services:"
curl -s http://localhost:8500/v1/catalog/services | python3 -m json.tool 2>/dev/null || \
    curl -s http://localhost:8500/v1/catalog/services

echo ""
echo "Service health:"
curl -s 'http://localhost:8500/v1/health/state/passing' | python3 -m json.tool 2>/dev/null | head -50 || \
    curl -s 'http://localhost:8500/v1/health/state/passing' | head -200

echo ""
echo "Test environment is ready!"
echo ""
echo "To run the gateway locally:"
echo "  export ZUUL_CONSUL_AGENT_HOST=localhost"
echo "  export ZUUL_CONSUL_AGENT_PORT=8500"
echo "  export ZUUL_DEFAULT_ENVIRONMENT=dev"
echo "  export ZUUL_REACHABLE_ENVIRONMENTS=dev:test"
echo "  ./gradlew :app:run"
echo ""
echo "To run functional tests:"
echo "  ./gradlew :app:functionalTest"
echo ""
echo "To stop the environment:"
echo "  ./docker/stop-test-env.sh"
