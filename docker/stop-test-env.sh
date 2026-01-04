#!/bin/bash
# Stop the Docker Compose test environment

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Stopping Zuul Consul test environment..."

docker-compose down -v

echo "Test environment stopped."
