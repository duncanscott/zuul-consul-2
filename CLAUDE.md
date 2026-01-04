# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew clean build

# Run gateway locally (requires Consul)
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
./gradlew :app:run

# Build fat JAR for deployment
./gradlew :app:fatJar
java -jar app/build/libs/app-*-all.jar

# Run tests
./gradlew test                    # Unit tests
./gradlew :app:functionalTest     # Functional tests (requires Docker stack)

# Full functional test with Docker environment
./functional-tests.sh

# Test with nginx proxy
docker-compose -f docker/docker-compose.yml --profile nginx up -d
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"

# Validate filters independently
./gradlew :filters:build
```

## Architecture Overview

Zuul Consul Gateway 2 is a Zuul 2-based API gateway with Consul service discovery and URL tag-based routing.

### Request Flow

1. **ConsulRoutingFilter** (Groovy, `filters/inbound/`) - Parses incoming URI to extract service name, tags, and path. URI pattern: `/{tag:value}/.../serviceName/path`
2. **ContextRootFilter** (Java) - Applies context-root prefix from Consul service tags
3. **ConsulOriginManager** - Creates/manages `ConsulNettyOrigin` instances per service+tags combination
4. **ConsulServerResolver** - Resolves healthy instances from `ConsulServiceCache` for load balancing
5. **StatsFilter** (Groovy, `filters/outbound/`) - Logs response metrics

### Key Components

| Package | Purpose |
|---------|---------|
| `consul/` | Consul client integration: `ConsulServiceRegistry` (Vert.x-based, watches + caching), `ConsulServiceCache` (thread-safe), `ConsulService` (instance wrapper) |
| `discovery/` | Zuul discovery integration: `ConsulServerResolver`, `ConsulDiscoveryResult` |
| `origins/` | Connection pooling: `ConsulNettyOrigin`, `ConsulOriginManager` |
| `server/` | Bootstrap: `ZuulConsulServer` (main), `ConsulServerStartup` (Netty config), `EnvironmentConfig` (env var overrides) |

### Service Discovery

- Uses Vert.x Consul client with blocking queries for real-time updates
- Catalog watch detects service add/remove; health state watch detects instance changes
- Fallback periodic refresh (configurable via `ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES`)
- Services matched by tags: `env:`, `version:`, `context-root!`, `docs!`

### Configuration Precedence

Environment variables override `application.properties`. Key env vars:
- `ZUUL_CONSUL_*` - Consul connection
- `ZUUL_RIBBON_*` - Connection/read timeouts, retry settings
- `ZUUL_DEFAULT_ENVIRONMENT` - Default `env:` tag
- `ZUUL_SSL_CERT_PATH`/`ZUUL_SSL_KEY_PATH` - Enable HTTPS

## Code Conventions

- Java packages: `org.jadetipi.zuulconsul.*`
- Groovy filters: stateless, `@Slf4j`, named `*Filter.groovy`
- Context keys: `UPPER_SNAKE_CASE` in filter classes
- Tests: JUnit 5 for Java, Spock for Groovy (`*Spec` or `*Test`)
- Java 21, Groovy 5

## Docker Test Environment

```bash
./docker/start-test-env.sh   # Start Consul + mock services
./docker/stop-test-env.sh    # Cleanup
```

Services: Consul (8500), hello-service (env:dev/test instances), echo-service. Gateway at localhost:9091.
