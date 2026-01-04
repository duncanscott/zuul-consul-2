#!/bin/bash
# Stop the Docker Compose test environment

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Stopping Zuul Consul test environment..."

# Stop all profiles (couchdb, nginx, full) to ensure all containers are removed
docker-compose --profile couchdb --profile nginx --profile full down -v

echo "Test environment stopped."
