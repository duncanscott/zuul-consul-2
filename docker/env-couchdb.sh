#!/bin/bash
# Source this file to set environment variables for running the gateway with CouchDB
# Usage: source docker/env-couchdb.sh

export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
export ZUUL_REACHABLE_ENVIRONMENTS=dev:test
export ZUUL_COUCHDB_ENABLED=true
export ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
export ZUUL_COUCHDB_USER=admin
export ZUUL_COUCHDB_PASSWORD=password
export ZUUL_BUFFER_REQUEST_BODY=true

echo "Environment variables set for CouchDB testing:"
echo "  ZUUL_CONSUL_AGENT_HOST=$ZUUL_CONSUL_AGENT_HOST"
echo "  ZUUL_CONSUL_AGENT_PORT=$ZUUL_CONSUL_AGENT_PORT"
echo "  ZUUL_DEFAULT_ENVIRONMENT=$ZUUL_DEFAULT_ENVIRONMENT"
echo "  ZUUL_REACHABLE_ENVIRONMENTS=$ZUUL_REACHABLE_ENVIRONMENTS"
echo "  ZUUL_COUCHDB_ENABLED=$ZUUL_COUCHDB_ENABLED"
echo "  ZUUL_COUCHDB_URL=$ZUUL_COUCHDB_URL"
echo "  ZUUL_COUCHDB_USER=$ZUUL_COUCHDB_USER"
echo "  ZUUL_COUCHDB_PASSWORD=********"
echo "  ZUUL_BUFFER_REQUEST_BODY=$ZUUL_BUFFER_REQUEST_BODY"
echo ""
echo "Now run: ./gradlew :app:run"
