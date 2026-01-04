#!/bin/sh
# Register services with Consul
# Services are registered with host.docker.internal addresses for access from host

CONSUL_URL="http://consul:8500"

# Use 127.0.0.1 for services accessible from the host via exposed ports
HOST_ADDRESS="127.0.0.1"

echo "Waiting for Consul to be ready..."
until curl -s "${CONSUL_URL}/v1/status/leader" | grep -q .; do
    sleep 1
done
echo "Consul is ready!"

echo "Registering hello-service (dev) at ${HOST_ADDRESS}:18081..."
curl -X PUT "${CONSUL_URL}/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "hello-service-dev-1",
    "Name": "hello-service",
    "Address": "'"${HOST_ADDRESS}"'",
    "Port": 18081,
    "Tags": ["env:dev", "version:default"],
    "Check": {
      "HTTP": "http://hello-dev:80/health",
      "Interval": "10s",
      "Timeout": "5s"
    }
  }'

echo ""
echo "Registering hello-service (test) at ${HOST_ADDRESS}:18082..."
curl -X PUT "${CONSUL_URL}/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "hello-service-test-1",
    "Name": "hello-service",
    "Address": "'"${HOST_ADDRESS}"'",
    "Port": 18082,
    "Tags": ["env:test", "version:default"],
    "Check": {
      "HTTP": "http://hello-test:80/health",
      "Interval": "10s",
      "Timeout": "5s"
    }
  }'

echo ""
echo "Registering echo-service at ${HOST_ADDRESS}:18083..."
curl -X PUT "${CONSUL_URL}/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "echo-service-1",
    "Name": "echo-service",
    "Address": "'"${HOST_ADDRESS}"'",
    "Port": 18083,
    "Tags": ["env:dev", "env:test", "version:default"],
    "Check": {
      "HTTP": "http://echo-service:80/health",
      "Interval": "10s",
      "Timeout": "5s"
    }
  }'

echo ""
echo "All services registered!"

# List registered services
echo ""
echo "Registered services:"
curl -s "${CONSUL_URL}/v1/agent/services" | head -c 500
echo ""

# Keep container running for a moment to allow health checks to pass
echo "Waiting for health checks..."
sleep 15

echo "Service registration complete. Container exiting."
