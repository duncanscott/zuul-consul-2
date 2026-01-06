# Zuul Consul Gateway 2

A lightweight API gateway built on **Netflix Zuul 2** with **Consul-based service discovery** and a simple, expressive **URL tag routing model**.

This project is designed for platform / SRE / backend engineering teams who want a **stable, observable, debuggable routing layer** — without adopting a full service mesh. It is based on operational experience gained from years of running Zuul 1 and Consul routing in production environments.

---

## ✨ Key Features

### 🚏 URL Tag–Based Routing
Route requests to services using clear, human‑readable tags in the path:

```
/env:dev/my-service/api/users
/env:prd/version:v2/my-service/health
/my-service/api/status          ← default tags apply
```

Tags such as `env:dev` or `version:v2` determine how the request is routed via Consul‑registered services.

This makes it easy to:
✔ switch environments  
✔ test new versions  
✔ perform gradual migrations  
✔ debug routing behavior

---

### 🔍 Consul‑Powered Service Discovery
- Uses **Consul health checks** to determine eligible backends
- Caches state locally for performance
- Supports real‑time Consul updates

---

### ⚖️ Load Balancing
- Simple round‑robin
- Avoids unhealthy instances
- No per‑request Consul lookups

---

### 🏗 Built on Zuul 2
Zuul 2 provides:
- Non‑blocking async architecture (Netty)
- High throughput
- Stability under load

This project uses Zuul as a dependency — not a fork — so you benefit from upstream stability without inheriting codebase complexity.

---

## 🚀 Quick Start

Clone and build:

```
git clone https://github.com/duncanscott/zuul-consul-2.git
cd zuul-consul-2
./gradlew build
```

Run the gateway (pointing at your Consul agent or the included demo stack):

```
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
./gradlew :app:run
```

See **Demo Stack & Functional Tests** below for a turnkey Consul + service lab that exercises these commands end-to-end, plus `./functional-tests.sh` which boots the stack and runs tests in one step.

---

## 🧪 Demo Stack & Functional Tests

A ready-to-use Docker Compose environment lives in `docker/docker-compose.yml`. Run it together with the gateway and functional specs via the single command:

```
./functional-tests.sh
```

The script boots Consul plus the sample services, waits for health checks, exports the standard `ZUUL_*` variables, launches `:app:run`, executes `:app:functionalTest`, and performs cleanup.

### Running with CouchDB Stats Logging

To test the CouchDB request stats feature, use the `--couchdb` flag:

```bash
./functional-tests.sh --couchdb
```

This will:
- Start CouchDB alongside Consul and the sample services
- Configure the gateway with `ZUUL_COUCHDB_ENABLED=true` and related environment variables
- Enable request body buffering (`ZUUL_BUFFER_REQUEST_BODY=true`)
- Run all functional tests including `CouchDbStatsSpec`

To run only the CouchDB-specific tests:

```bash
./functional-tests.sh --couchdb
./gradlew :app:functionalTest --tests "*CouchDbStatsSpec*"
```

### Manual Testing

If you prefer to drive components manually, you can start the stack (Consul, `hello-service` for `env:dev` and `env:test`, `echo-service`, and optional nginx proxy) with:

```
./docker/start-test-env.sh
```

Then run the gateway locally (see Quick Start) to route through the containerized services at `http://localhost:9091`, and stop everything afterwards via `./docker/stop-test-env.sh`.

With the stack running, the functional suite can also be invoked directly:

```
./gradlew :app:functionalTest
```

### Nginx Proxy Tests

Enable nginx-specific traffic flows by starting the proxy profile and running the targeted specs:

```
docker-compose -f docker/docker-compose.yml --profile nginx up -d
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
```

Nginx listens on `http://localhost:8080` and `https://localhost:8443`, forwarding to the host's Zuul instance while applying the hardened headers and TLS handling described below.

### CouchDB Tests (Manual)

To run CouchDB tests manually and keep the environment running for exploration:

**Step 1: Start the Docker environment with CouchDB**

```bash
cd docker
docker-compose --profile couchdb up -d
cd ..
```

This starts:
- Consul (port 8500)
- CouchDB (port 5994, credentials: admin/password)
- Sample backend services (hello-service, echo-service)
- Service registrar (registers services with Consul)

**Step 2: Wait for services to be ready**

```bash
# Wait for Consul
until curl -sf http://localhost:8500/v1/status/leader > /dev/null; do sleep 1; done
echo "Consul is ready"

# Wait for CouchDB
until curl -sf http://localhost:5994/_up > /dev/null; do sleep 1; done
echo "CouchDB is ready"

# Verify database exists (created by couchdb-init container)
curl -u admin:password http://localhost:5994/zuul-consul
```

**Step 3: Start the gateway (in a separate terminal)**

```bash
# Source the environment script and start the gateway
source docker/env-couchdb.sh
./gradlew :app:run
```

Or set variables manually:

```bash
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
export ZUUL_REACHABLE_ENVIRONMENTS=dev:test
export ZUUL_COUCHDB_ENABLED=true
export ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
export ZUUL_COUCHDB_USER=admin
export ZUUL_COUCHDB_PASSWORD=password
export ZUUL_BUFFER_REQUEST_BODY=true

./gradlew :app:run
```

**Step 4: Run the CouchDB functional tests (in another terminal)**

```bash
export ZUUL_COUCHDB_ENABLED=true
./gradlew :app:functionalTest --tests "*CouchDbStatsSpec*"
```

**Step 5: Explore CouchDB data**

```bash
# View all documents
curl -u admin:password 'http://localhost:5994/zuul-consul/_all_docs?include_docs=true'

# Query by timestamp range
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/by_timestamp?include_docs=true'

# Query by service
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/by_service?startkey=["hello-service",""]&endkey=["hello-service","\ufff0"]&include_docs=true'

# Open Fauxton UI in browser
open http://localhost:5994/_utils/
```

**Step 6: Stop the environment**

```bash
# Stop the gateway with Ctrl+C in its terminal

# Stop Docker containers
./docker/stop-test-env.sh
```

---

## ⚙️ Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ZUUL_CONSUL_AGENT_HOST` | Consul agent hostname | `localhost` |
| `ZUUL_CONSUL_AGENT_PORT` | Consul agent port | `8500` |
| `ZUUL_CONSUL_DATACENTER` | Consul datacenter | (none) |
| `ZUUL_CONSUL_TOKEN` | Consul ACL token | (none) |
| `ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES` | Fallback refresh interval for service catalog | `15` |
| `ZUUL_DEFAULT_ENVIRONMENT` | Default environment tag applied when URL omits `env:` | `dev` |
| `ZUUL_REACHABLE_ENVIRONMENTS` | Colon-separated list of allowed environments | (all) |
| `ZUUL_DEFAULT_TAGS` | Slash-separated default tags (e.g., `version:default`) | (none) |
| `ZUUL_SERVER_PORT` | Server listening port | `9091` (HTTP) or `8443` (HTTPS) |
| `ZUUL_SSL_CERT_PATH` | Path to SSL certificate (PEM format); enables HTTPS | (none) |
| `ZUUL_SSL_KEY_PATH` | Path to SSL private key (PEM format) | (none) |
| `ZUUL_JWKS_URL` | JWKS endpoint URL for JWT validation | (none) |
| `ZUUL_RIBBON_CONNECT_TIMEOUT` | Connection timeout in milliseconds | `2000` |
| `ZUUL_RIBBON_READ_TIMEOUT` | Read timeout in milliseconds | `30000` |
| `ZUUL_RIBBON_MAX_AUTO_RETRIES` | Max retries on same server | `0` |
| `ZUUL_RIBBON_MAX_AUTO_RETRIES_NEXT_SERVER` | Max retries on next server | `1` |
| `JAVA_OPTS` | JVM options (e.g., `-Xms512m -Xmx1024m`) | (none) |
| `ZUUL_COUCHDB_ENABLED` | Enable CouchDB request stats logging | `false` |
| `ZUUL_COUCHDB_URL` | CouchDB database URL | (none) |
| `ZUUL_COUCHDB_USER` | CouchDB username | (none) |
| `ZUUL_COUCHDB_PASSWORD` | CouchDB password | (none) |

When `ZUUL_DEFAULT_ENVIRONMENT` is set, the gateway injects `env:<value>` into the default tag list and adds `<value>` to the reachable-environment list.

### Application Properties

All environment variables above override the corresponding properties in `app/src/main/resources/application.properties`:

```properties
zuul.server.port.main=9091
zuul.consul.refresh.interval.minutes=5
zuul.ribbon.ConnectTimeout=2000
zuul.ribbon.ReadTimeout=30000
zuul.ribbon.MaxAutoRetries=0
zuul.ribbon.MaxAutoRetriesNextServer=1
```

---

## 🏷 Consul Service Registration

Backend services should be registered in Consul with appropriate tags:

| Tag Prefix | Description | Example |
|------------|-------------|---------|
| `env:` | Environment tag | `env:dev`, `env:prd` |
| `version:` | Version tag | `version:v1`, `version:v2` |
| `context-root!` | Base path for the service | `context-root!/api/v1` |
| `docs!` | Documentation URL | `docs!https://docs.example.com` |

### Example Registration

```json
{
  "Name": "my-service",
  "ID": "my-service-1",
  "Address": "10.0.1.5",
  "Port": 8080,
  "Tags": [
    "env:dev",
    "version:default",
    "version:v1",
    "context-root!/api"
  ],
  "Check": {
    "HTTP": "http://10.0.1.5:8080/health",
    "Interval": "10s"
  }
}
```

### Service Protocol Detection

The gateway determines the protocol (HTTP or HTTPS) to use when connecting to backend services using the following priority:

1. **`meta.scheme`** — If the service metadata contains a `scheme` key, use its value (e.g., `"https"`, `"http"`). This aligns with Spring Cloud's `spring.cloud.consul.discovery.scheme`.
2. **`meta.secure`** — If the service metadata contains `"secure": "true"`, use `https`. This provides Spring Cloud compatibility via `spring.cloud.consul.discovery.metadata.secure=true`.
3. **Address prefix** — If the `Address` field starts with `https://` or `http://`, use that protocol
4. **Default** — Use `http`

#### Examples

| meta.scheme | meta.secure | Address | Resulting Protocol |
|-------------|-------------|---------|-------------------|
| `"https"` | (any) | `prospero.example.com` | `https` |
| `"https"` | (any) | `http://prospero.example.com` | `https` (scheme takes precedence) |
| (not set) | `"true"` | `prospero.example.com` | `https` |
| (not set) | `"false"` | `prospero.example.com` | `http` (default) |
| (not set) | (not set) | `https://prospero.example.com` | `https` |
| (not set) | (not set) | `prospero.example.com` | `http` (default) |

#### Registration with Scheme in Metadata

```json
{
  "Name": "my-secure-service",
  "ID": "my-secure-service-1",
  "Address": "secure.example.com",
  "Port": 443,
  "Tags": ["env:prd", "context-root!/api"],
  "Meta": {
    "scheme": "https"
  },
  "Check": {
    "HTTP": "https://secure.example.com:443/health",
    "Interval": "10s"
  }
}
```

#### Registration with Protocol in Address

Alternatively, include the protocol in the address:

```json
{
  "Name": "my-secure-service",
  "ID": "my-secure-service-1",
  "Address": "https://secure.example.com",
  "Port": 443,
  "Tags": ["env:prd", "context-root!/api"],
  "Check": {
    "HTTPS": "https://secure.example.com:443/health",
    "Interval": "10s"
  }
}
```

---

## 🧠 Design Goals

### Keep It Simple
- Use Consul directly — no mesh, no sidecars, no control plane
- Routing logic should be readable in 10 seconds
- URL should reflect behavior

### Production‑Friendly
- Deterministic behavior
- Clear logging + debugging
- Failure‑aware routing

### Debuggable
If routing is confusing, the system has failed.  
So the design favors clarity first.

---

## 🔒 Deployment Model

Typical production deployment looks like:

```
Client → nginx (TLS, headers, limits) → Zuul Consul Gateway → Backend Services
```

Recommended nginx responsibilities:
- TLS termination
- CORS handling
- Logging overrides
- Request size enforcement
- Strip / rewrite trusted headers

Zuul focuses only on routing.

---

## 🧱 Example NGINX Configuration

The demo stack ships with a hardened reverse proxy in `docker/nginx-proxy/`. `nginx.conf` contains the global tuning, while `conf.d/zuul-consul.conf` defines the upstream keepalive block, proxy headers, gzip, and CORS / security response headers. The entrypoint script auto-generates self-signed certificates inside `docker/nginx-proxy/ssl/` (bring your own `server.crt` / `server.key` to override them).

Run the proxy alongside the services with:

```
docker-compose -f docker/docker-compose.yml --profile nginx up -d
```

and reach the gateway via `http://localhost:8080`, `https://localhost:8443`, or the status endpoint on `http://localhost:8888/health`. These same settings are exercised in `NginxProxySpec` when `NGINX_TESTS=true` during `:app:functionalTest`.

---

## 📦 Deployment

### Running the JAR

```bash
./gradlew :app:fatJar
java -jar app/build/libs/app-*-all.jar
```

### Server Setup (One-Time)

**1. Create directory structure:**

```bash
mkdir -p /opt/zuul-consul-releases
chown zuul:zuul /opt/zuul-consul-releases
chmod 770 /opt/zuul-consul-releases
ln -sfn /opt/zuul-consul-releases/current /opt/zuul-consul
```

**2. Configure sudoers:**

```bash
visudo -f /etc/sudoers.d/zuul-consul
```

Add:
```
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl restart zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl start zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl stop zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl status zuul-consul
```

**3. Create environment file** `/etc/zuul-consul/zuul-env.sh`:

```bash
ZUUL_CONSUL_AGENT_HOST=localhost
ZUUL_CONSUL_AGENT_PORT=8500
ZUUL_DEFAULT_ENVIRONMENT=dev
ZUUL_REACHABLE_ENVIRONMENTS='dev:int:uat:prd'
ZUUL_DEFAULT_TAGS='version:default'
ZUUL_CONSUL_TOKEN='<consul-acl-token>'
JAVA_OPTS='-Xms512m -Xmx1024m'
```

**4. Create systemd service** `/etc/systemd/system/zuul-consul.service`:

```ini
[Unit]
Description=Zuul Consul Gateway
After=network.target

[Service]
Type=simple
User=zuul
Group=zuul
EnvironmentFile=/etc/zuul-consul/zuul-env.sh
ExecStart=/opt/zuul-consul/bin/zuul-consul
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable zuul-consul
```

### Docker

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app/build/libs/app-*-all.jar /app/zuul-consul.jar
ENV ZUUL_CONSUL_AGENT_HOST=localhost
ENV ZUUL_CONSUL_AGENT_PORT=8500
ENV ZUUL_DEFAULT_ENVIRONMENT=dev
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "/app/zuul-consul.jar"]
```

```bash
./gradlew :app:fatJar
docker build -t zuul-consul .
docker run -p 9091:9091 -e ZUUL_CONSUL_AGENT_HOST=consul.example.com zuul-consul
```

### Jenkins Deployment

1. Update version in `gradle.properties`
2. Commit and push
3. Run `./tag.sh` to create and push the git tag
4. Jenkins pipeline builds, tests, and deploys (dev → int → prd)

---

## 🏥 Health & Failure Behavior

| Scenario | Expected Behavior |
|---------|------------------|
| Consul unavailable | Continue routing using last known good state |
| Service unhealthy | Automatically removed from rotation |
| No matching service or tags | Request rejected cleanly |
| Long‑latency backend | Timed‑out and surfaced appropriately |

The root path `/` returns service registry information when no service name is specified.

---

## 📡 Observability

### Logging

The gateway logs to three destinations:

| Appender | Location | Format | Purpose |
|----------|----------|--------|---------|
| STDOUT | Console | Pattern | Development, systemd/journald |
| ROLLER | `~/logs/zuul-consul.log` | Pattern | Traditional file logging |
| JSON | `~/json-logs/zuul-consul-json.log` | Logstash JSON | Log aggregation (ELK/Splunk) |

File appenders use rolling policies: 10MB max size, 21 rotated files.

Set the root log level via environment variable:

```bash
export ROOT_LOG_LEVEL=DEBUG  # default: INFO
```

### CouchDB Request Stats

Optionally store request statistics in CouchDB for analysis and debugging. This feature is disabled by default.

#### Starting CouchDB

The docker-compose stack includes a CouchDB instance:

```bash
# Start CouchDB only
docker-compose -f docker/docker-compose.yml --profile couchdb up -d

# Or start full stack including CouchDB
docker-compose -f docker/docker-compose.yml --profile full up -d
```

CouchDB will be available at `http://localhost:5994` with credentials `admin/password`. The `zuul-consul` database is created automatically.

#### Configuration

Enable CouchDB stats logging with these environment variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `ZUUL_COUCHDB_ENABLED` | Enable/disable the filter | `true` |
| `ZUUL_COUCHDB_URL` | CouchDB database URL | `http://localhost:5994/zuul-consul` |
| `ZUUL_COUCHDB_USER` | CouchDB username | `admin` |
| `ZUUL_COUCHDB_PASSWORD` | CouchDB password | `password` |
| `ZUUL_BUFFER_REQUEST_BODY` | Enable request body capture | `true` |

**Note:** Request body capture is disabled by default. Enable it to log JSON request bodies.
Response bodies with `Content-Type: application/json` are captured automatically when CouchDB logging is enabled.

Example:

```bash
export ZUUL_COUCHDB_ENABLED=true
export ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
export ZUUL_COUCHDB_USER=admin
export ZUUL_COUCHDB_PASSWORD=password
```

#### Document Structure

Each request creates a JSON document using [Elastic Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/ecs-field-reference.html) and [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/http/http-spans/):

```json
{
  "_id": "b7ad6b7169203331",
  "@timestamp": "2026-01-04T10:30:00-08:00",
  "type": "request_stats",
  "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span.id": "b7ad6b7169203331",
  "service.name": "my-service",
  "labels.team": "my",
  "labels.env": "dev",
  "labels.version": "v1",
  "url.full": "http://10.0.1.5:8080/api/users",
  "url.original": "/env:dev/my-service/api/users",
  "url.path": "/api/users",
  "server.address": "10.0.1.5",
  "server.port": 8080,
  "http.request.method": "POST",
  "http.response.status_code": 200,
  "event.duration": 45,
  "event.outcome": "success",
  "client.ip": "192.168.1.100",
  "client.address": "192.168.1.100",
  "http.request.body.content": { "name": "John", "email": "john@example.com" },
  "http.response.body.content": { "id": 123, "status": "created" }
}
```

**Key features:**
- Document `_id` is set to `span.id` (unique per operation, avoids conflicts in distributed traces)
- Field names use dots (`.`) per ECS conventions
- Body content is stored as JSON objects (not escaped strings)

| Field | Standard | Description |
|-------|----------|-------------|
| `_id` | CouchDB | Document ID, set to span.id (unique per operation) |
| `@timestamp` | ECS | ISO8601 formatted request timestamp |
| `type` | — | Always `request_stats` (for CouchDB views) |
| `trace.id` | ECS | W3C trace ID (shared across distributed trace) |
| `span.id` | ECS | W3C span ID (same as `_id`) |
| `service.name` | ECS | Target service name from Consul |
| `labels.team` | ECS | Team prefix from service name (before first `-`) |
| `labels.*` | ECS | Dynamic labels from URL tags (e.g., `labels.env`, `labels.version`) |
| `url.full` | ECS | Full URI of the selected backend instance |
| `url.original` | ECS | Original request path including tags |
| `url.path` | ECS | Path sent to backend service |
| `server.address` | OTel | Backend host |
| `server.port` | OTel | Backend port |
| `http.request.method` | ECS | HTTP method (GET, POST, etc.) |
| `http.response.status_code` | ECS | HTTP response status code |
| `event.duration` | ECS | Request duration in milliseconds |
| `event.outcome` | ECS | `success` or `failure` |
| `error.type` | ECS | `client_error` (4xx) or `server_error` (5xx) |
| `client.ip` | ECS | Client IP from X-Forwarded-For header |
| `client.address` | OTel | Client address (same as `client.ip`) |
| `http.request.body.content` | ECS | JSON request body object (requires `ZUUL_BUFFER_REQUEST_BODY=true`) |
| `http.response.body.content` | ECS | JSON response body object |

**Body content notes:**
- Body fields store actual JSON objects/arrays (not escaped strings)
- Only JSON bodies are captured (validated by checking `Content-Type` header and parsing)
- Bodies larger than 1MB are skipped to avoid memory issues
- Request body capture requires explicit opt-in via `ZUUL_BUFFER_REQUEST_BODY=true`

#### CouchDB Views

The gateway automatically creates a design document (`_design/stats`) with views for querying request stats. Views are created on startup if they don't exist.

| View | Key | Description |
|------|-----|-------------|
| `by_timestamp` | `@timestamp` | Query by time range using `startkey`/`endkey` |
| `by_service` | `[service.name, @timestamp]` | Filter by service, then time range |
| `by_status` | `[http.response.status_code, @timestamp]` | Filter by HTTP status, then time range |
| `errors` | `@timestamp` | Query only requests with `event.outcome=failure` |

**Query examples:**

```bash
# Get all requests in a time range
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/by_timestamp?startkey="2024-01-01T00:00:00"&endkey="2024-01-02T00:00:00"&include_docs=true'

# Get requests for a specific service in a time range
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/by_service?startkey=["my-service","2024-01-01"]&endkey=["my-service","2024-01-02"]&include_docs=true'

# Get all 500 errors in a time range
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/by_status?startkey=[500,"2024-01-01"]&endkey=[500,"2024-01-02"]&include_docs=true'

# Get all error requests
curl -u admin:password \
  'http://localhost:5994/zuul-consul/_design/stats/_view/errors?include_docs=true'
```

#### Viewing Data

Access CouchDB Fauxton UI at `http://localhost:5994/_utils/` to browse documents.

Query all documents:
```bash
curl -u admin:password http://localhost:5994/zuul-consul/_all_docs?include_docs=true
```

**Looking up by span ID:** Each document's `_id` matches its `span.id`, so you can look up a request directly using the span ID from logs:
```bash
# Get document by span ID
curl -u admin:password http://localhost:5994/zuul-consul/b7ad6b7169203331
```

**Finding all spans in a trace:** Use Mango query to find all documents with the same `trace.id`:
```bash
curl -u admin:password -X POST http://localhost:5994/zuul-consul/_find \
  -H "Content-Type: application/json" \
  -d '{"selector": {"trace.id": "4bf92f3577b34da6a3ce929d0e0e4736"}}'
```

The stats are posted asynchronously to avoid impacting request latency.

### Recommended Practices

You should configure:
- request IDs (`X‑Request‑Id`)
- structured logs including:
  - routing tags used
  - chosen service instance
  - request latency
  - upstream response code
- metrics per service + tag

Prometheus metrics support is planned.

---

## 🔍 Status & Intent

This project reflects real‑world operational experience at the **DOE Joint Genome Institute**, but this repository is a **fresh, clean rewrite** intended for wider use.

It intentionally avoids complexity — preferring predictable, transparent routing behavior over feature bloat.

---

## 🗂 Project Structure

```
zuul-consul/
├── app/                                    # Main application (Java)
│   └── src/main/java/org/jadetipi/zuulconsul/
│       ├── consul/                         # Consul client integration
│       ├── discovery/                      # Zuul discovery integration
│       ├── origins/                        # Origin management
│       ├── server/                         # Server startup
│       └── service/                        # URI parsing utilities
├── filters/                                # Groovy filters
│   └── src/main/groovy/
│       ├── inbound/
│       │   ├── BodyBufferFilter.groovy     # Request body buffering (optional)
│       │   ├── ConsulRoutingFilter.groovy  # Request routing filter
│       │   └── RequestIdFilter.groovy      # Trace ID assignment
│       └── outbound/
│           ├── StatsFilter.groovy          # Response logging filter
│           └── CouchDbStatsFilter.groovy   # Optional CouchDB stats
├── docker/                                 # Test environment
│   ├── docker-compose.yml
│   └── nginx-proxy/                        # Reference nginx config
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 🔧 Adding Custom Filters

Create Groovy filters in `filters/src/main/groovy/`.

**Important:** Custom filters must be registered in `ZuulConsulServer.java` to be loaded. Add your filter class to the `FILTER_TYPES` set:

```java
static {
    Set<Class<? extends ZuulFilter<?, ?>>> classes = new LinkedHashSet<>();
    // ... existing filters ...
    classes.add(MyCustomFilter.class);  // Add your filter here
    FILTER_TYPES = Collections.unmodifiableSet(classes);
}
```

### Inbound Filter Example

```groovy
package inbound

import com.netflix.zuul.filters.http.HttpInboundSyncFilter
import com.netflix.zuul.message.http.HttpRequestMessage

class MyCustomFilter extends HttpInboundSyncFilter {

    @Override
    int filterOrder() { return 50 }  // Before ConsulRoutingFilter (order 100)

    @Override
    boolean shouldFilter(HttpRequestMessage request) { return true }

    @Override
    HttpRequestMessage apply(HttpRequestMessage request) {
        request.getHeaders().set("X-Custom-Header", "value")
        return request
    }
}
```

### Outbound Filter Example

```groovy
package outbound

import com.netflix.zuul.filters.http.HttpOutboundSyncFilter
import com.netflix.zuul.message.http.HttpResponseMessage

class MyResponseFilter extends HttpOutboundSyncFilter {

    @Override
    int filterOrder() { return 100 }

    @Override
    boolean shouldFilter(HttpResponseMessage response) { return true }

    @Override
    HttpResponseMessage apply(HttpResponseMessage response) {
        response.getHeaders().set("Access-Control-Allow-Origin", "*")
        return response
    }
}
```

---

## 🔗 Distributed Tracing

### W3C Trace Context Support

Zuul Consul supports [W3C Trace Context](https://www.w3.org/TR/trace-context/) for distributed tracing:

| Header | Format | Description |
|--------|--------|-------------|
| `traceparent` | `00-{trace-id}-{span-id}-{flags}` | W3C standard trace propagation |
| `tracestate` | vendor-specific | Optional vendor-specific trace data |

Example: `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-b7ad6b7169203331-01`

### OpenTelemetry Integration

The gateway uses a hybrid approach for trace IDs:

1. **With OpenTelemetry Agent**: Uses trace ID from the agent's span context
2. **Without Agent**: Generates W3C-compatible 32-hex-char trace IDs

To enable OpenTelemetry, add the Java agent to `JAVA_OPTS`:

```bash
JAVA_OPTS='-javaagent:/path/to/opentelemetry-javaagent.jar'
OTEL_SERVICE_NAME=zuul-consul
OTEL_EXPORTER_OTLP_ENDPOINT=http://your-apm-server:4317
```

The `trace.id` appears in all log messages and correlates with APM traces.

### W3C Trace Context Headers

Zuul Consul uses W3C Trace Context headers for distributed tracing:

| Header | Description |
|--------|-------------|
| `traceparent` | W3C trace context: `{version}-{trace-id}-{span-id}-{flags}` |
| `tracestate` | Optional vendor-specific trace data (propagated if present) |

### MDC Fields

MDC fields for structured logging (ECS/OTel naming):

| MDC Field | OTel Equivalent | Description |
|-----------|-----------------|-------------|
| `trace.id` | (same) | W3C trace ID (32 hex chars) |
| `span.id` | (same) | W3C span ID (16 hex chars) |
| `http.response.status_code` | (same) | HTTP response status code |
| `http.request.method` | (same) | HTTP method |
| `url.path` | (same) | Request path |
| `service.name` | (same) | Target service name |
| `service.host.name` | `server.address` | Backend host |
| `service.port` | `server.port` | Backend port |
| `service.url.path` | — | Backend path |
| `fields.team` | — | Team prefix from service name |
| `milliseconds` | — | Request duration |
| `tag.*` | — | Dynamic tags (e.g., `tag.env`, `tag.version`) |
| `forwarded_for_ip` | `client.address` | Client IP from X-Forwarded-For |

Fields with OTel equivalents are logged under both names for compatibility with OpenTelemetry tooling. See [OpenTelemetry HTTP Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/http/http-spans/).

---

## 📄 License

Apache License 2.0 — see `LICENSE`.

---

## 🙏 Acknowledgements

This project is built on the powerful foundations of **Netflix Zuul 2** and **HashiCorp Consul**.

---

## 🤝 Contributing

Contributions, feature discussions, and testing feedback are welcome.
Please open an Issue or Pull Request.

---

## 🧭 Roadmap

Planned additions include:

- Health endpoint specification
- Prometheus metrics support
- More documentation & diagrams

---

## 📬 Contact

Repository owner: **Duncan Scott**  
Project: **Zuul Consul 2**

---

If you find this useful — a star or share helps others discover it 🙂
