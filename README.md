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

If you prefer to drive components manually, you can start the stack (Consul, `hello-service` for `env:dev` and `env:test`, `echo-service`, and optional nginx proxy) with:

```
./docker/start-test-env.sh
```

Then run the gateway locally (see Quick Start) to route through the containerized services at `http://localhost:9091`, and stop everything afterwards via `./docker/stop-test-env.sh`.

With the stack running, the functional suite can also be invoked directly:

```
./gradlew :app:functionalTest
```

Enable nginx-specific traffic flows by starting the proxy profile and running the targeted specs:

```
docker-compose -f docker/docker-compose.yml --profile nginx up -d
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
```

Nginx listens on `http://localhost:8080` and `https://localhost:8443`, forwarding to the host's Zuul instance while applying the hardened headers and TLS handling described below.

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

Example:

```bash
export ZUUL_COUCHDB_ENABLED=true
export ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
export ZUUL_COUCHDB_USER=admin
export ZUUL_COUCHDB_PASSWORD=password
```

#### Document Structure

Each request creates a JSON document:

```json
{
  "_id": "auto-generated",
  "timestamp": "2026-01-04T10:30:00-08:00",
  "type": "request_stats",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "b7ad6b7169203331",
  "service": "my-service",
  "team": "my",
  "method": "GET",
  "original_uri": "/env:dev/my-service/api/users",
  "path": "/api/users",
  "backend_uri": "http://10.0.1.5:8080/api/users",
  "status": 200,
  "duration_ms": 45,
  "error": false
}
```

| Field | Description |
|-------|-------------|
| `timestamp` | ISO8601 formatted request timestamp |
| `type` | Always `request_stats` (useful for CouchDB views) |
| `trace_id` | W3C trace ID for distributed tracing correlation |
| `span_id` | W3C span ID |
| `service` | Target service name from Consul |
| `team` | Team prefix extracted from service name (before first `-`) |
| `method` | HTTP method (GET, POST, etc.) |
| `original_uri` | Original request path including tags |
| `path` | Path sent to backend service |
| `backend_uri` | Full URI of the selected backend instance |
| `status` | HTTP response status code |
| `duration_ms` | Request duration in milliseconds |
| `error` | `true` for 4xx/5xx responses |
| `server_error` | `true` for 5xx responses |

#### Viewing Data

Access CouchDB Fauxton UI at `http://localhost:5994/_utils/` to browse documents.

Query all documents:
```bash
curl -u admin:password http://localhost:5994/zuul-consul/_all_docs?include_docs=true
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
│       │   └── ConsulRoutingFilter.groovy  # Request routing filter
│       └── outbound/
│           └── StatsFilter.groovy          # Response logging filter
├── docker/                                 # Test environment
│   ├── docker-compose.yml
│   └── nginx-proxy/                        # Reference nginx config
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 🔧 Adding Custom Filters

Create Groovy filters in `filters/src/main/groovy/`:

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

### Legacy Headers

For backward compatibility, these headers are also set:

| Header | Description |
|--------|-------------|
| `X-Zuul-Consul-Id` | Trace ID (same as W3C trace-id) |
| `X-Parent-Zuul-Consul-Id` | Parent trace ID (for nested calls) |
| `X-Root-Zuul-Consul-Id` | Root trace ID in the call chain |

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
