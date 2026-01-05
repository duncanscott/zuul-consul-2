#!/bin/bash
# Source this file to set environment variables for running the gateway (without CouchDB)
# Usage: source docker/env-dev.sh

export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
export ZUUL_REACHABLE_ENVIRONMENTS=dev:test

echo "Environment variables set for local development:"
echo "  ZUUL_CONSUL_AGENT_HOST=$ZUUL_CONSUL_AGENT_HOST"
echo "  ZUUL_CONSUL_AGENT_PORT=$ZUUL_CONSUL_AGENT_PORT"
echo "  ZUUL_DEFAULT_ENVIRONMENT=$ZUUL_DEFAULT_ENVIRONMENT"
echo "  ZUUL_REACHABLE_ENVIRONMENTS=$ZUUL_REACHABLE_ENVIRONMENTS"
echo ""
echo "Now run: ./gradlew :app:run"
