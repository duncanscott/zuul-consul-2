# Zuul Consul Gateway

A Zuul 2-based API gateway with Consul service discovery and tag-based routing.

## Overview

Zuul Consul is a reverse proxy that routes requests to backend services discovered via HashiCorp Consul. It supports tag-based routing, allowing requests to be directed to specific service instances based on environment, version, or other tags.

### Features

- **Consul Service Discovery** - Automatically discovers backend services from Consul
- **Real-Time Updates** - Uses Consul watches (blocking queries) for instant service changes
- **Tag-Based Routing** - Route requests using URL-embedded tags (e.g., `/env:dev/my-service/api`)
- **Non-Blocking Architecture** - Built on Zuul 2's Netty-based async infrastructure
- **Round-Robin Load Balancing** - Distributes requests across healthy service instances
- **Fallback Refresh** - Periodic refresh as backup for watch reliability
- **Groovy Filters** - Easy-to-write filters for request/response processing

## URL Routing Pattern

```
/{tag:value}/.../{serviceName}/{path}
```

### Examples

| URL | Service | Tags | Backend Path |
|-----|---------|------|--------------|
| `/env:dev/my-service/api/users` | my-service | env:dev | /api/users |
| `/env:prod/version:v2/my-service/health` | my-service | env:prod, version:v2 | /health |
| `/my-service/api/users` | my-service | (default tags) | /api/users |

## Building

```bash
./gradlew clean build
```

## Running

### Quick Start

```bash
./gradlew :app:run
```

### With Custom Configuration

```bash
export ZUUL_CONSUL_AGENT_HOST=consul.example.com
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
./gradlew :app:run
```

### Running the JAR

```bash
./gradlew :app:fatJar
java -jar app/build/libs/app-1.0.0-SNAPSHOT-all.jar
```

## Testing

### Functional Tests

Functional tests verify the gateway's routing behavior against a real Consul instance and backend services running in Docker.

**Quick Start (automated):**

```bash
./functional-tests.sh
```

This script automatically:
1. Starts the Docker test environment (Consul + mock services)
2. Starts the gateway
3. Runs the functional tests
4. Cleans up everything on exit

**Manual Steps:**

If you prefer to run the steps manually:

```bash
# Terminal 1: Start Docker environment
./docker/start-test-env.sh

# Terminal 2: Start the gateway
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
export ZUUL_REACHABLE_ENVIRONMENTS=dev:test
./gradlew :app:run

# Terminal 3: Run the tests
./gradlew :app:functionalTest

# When done
./docker/stop-test-env.sh
```

### Test Environment

The Docker test environment includes:
- **Consul** (port 8500) - Service discovery
- **hello-service** - Mock service with `env:dev` and `env:test` instances
- **echo-service** - Mock service that echoes request info

Services are registered with Consul and accessible via the gateway at `http://localhost:9091`.

### Nginx Proxy Testing

The test environment includes an optional nginx reverse proxy that demonstrates the recommended production configuration. This allows testing:
- Header forwarding (X-Forwarded-*, X-Real-IP)
- CORS handling
- HTTPS with security headers (HSTS, etc.)
- HTTP/1.1 keepalive connections

**SSL Certificates:**

The nginx container automatically generates self-signed SSL certificates on first startup if none exist. This is handled by the entrypoint script (`docker/nginx-proxy/docker-entrypoint.sh`). The generated certificates are:
- Stored in `docker/nginx-proxy/ssl/`
- Valid for localhost and 127.0.0.1
- Excluded from git (see `.gitignore`)

For custom certificates, place your files in `docker/nginx-proxy/ssl/`:
- `server.crt` - Certificate file
- `server.key` - Private key file

**Start nginx proxy:**

```bash
# Start all services including nginx
docker-compose -f docker/docker-compose.yml --profile nginx up -d

# Or start just nginx (requires gateway already running)
docker-compose -f docker/docker-compose.yml up -d nginx-proxy
```

**Access points:**
- HTTP: `http://localhost:8080`
- HTTPS: `https://localhost:8443`
- Status/metrics: `http://localhost:8888/health`

**Run nginx-specific tests:**

```bash
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
```

The nginx configuration in `docker/nginx-proxy/` serves as a reference implementation with:
- Upstream keepalive connections
- Modern TLS configuration (TLS 1.2/1.3)
- Security headers (HSTS, X-Frame-Options, X-Content-Type-Options)
- CORS with preflight handling
- Gzip compression
- Proper header forwarding

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ZUUL_CONSUL_AGENT_HOST` | Consul agent hostname | `localhost` |
| `ZUUL_CONSUL_AGENT_PORT` | Consul agent port | `8500` |
| `ZUUL_CONSUL_DATACENTER` | Consul datacenter | (none) |
| `ZUUL_CONSUL_TOKEN` | Consul ACL token | (none) |
| `ZUUL_DEFAULT_ENVIRONMENT` | Default environment tag applied when URL omits `env:` | `dev` |
| `ZUUL_REACHABLE_ENVIRONMENTS` | Colon-separated list of allowed environments (default env is added automatically) | (all) |
| `ZUUL_DEFAULT_TAGS` | Slash-separated default tags (e.g., `version:default` — no need to repeat `env:` here) | (none) |
| `ZUUL_SERVER_PORT` | Server listening port | `9091` (HTTP) or `8443` (HTTPS) |
| `ZUUL_SSL_CERT_PATH` | Path to SSL certificate (PEM format); enables HTTPS when set | (none) |
| `ZUUL_SSL_KEY_PATH` | Path to SSL private key (PEM format) | (none) |
| `ZUUL_JWKS_URL` | JWKS endpoint URL for JWT validation; disables JWT auth when not set | (none) |
| `JAVA_OPTS` | JVM options (e.g., `-Xms512m -Xmx1024m`) | (none) |

When `ZUUL_DEFAULT_ENVIRONMENT` is set, the gateway injects `env:<value>` into the default tag list and adds `<value>` to the reachable-environment list. Operators therefore only need to set that one variable to get legacy Zuul 1 behavior; `ZUUL_DEFAULT_TAGS` can focus on other defaults such as `version:` or custom tags.

### Application Properties

Configuration can also be set in `app/src/main/resources/application.properties`:

```properties
# Server port
zuul.server.port.main=9091

# Connection pool settings
zuul.ribbon.ConnectTimeout=2000
zuul.ribbon.ReadTimeout=30000
zuul.ribbon.MaxAutoRetries=0
zuul.ribbon.MaxAutoRetriesNextServer=1
```

## Consul Service Registration

Backend services should be registered in Consul with appropriate tags. Zuul Consul recognizes these special tag prefixes:

| Tag Prefix | Description | Example |
|------------|-------------|---------|
| `env:` | Environment tag | `env:dev`, `env:prod` |
| `version:` | Version tag | `version:v1`, `version:v2` |
| `context-root!` | Base path for the service | `context-root!/api/v1` |
| `docs!` | Documentation URL | `docs!https://docs.example.com` |

### Example Consul Service Registration

```json
{
  "Name": "my-service",
  "ID": "my-service-1",
  "Address": "10.0.1.5",
  "Port": 8080,
  "Tags": [
    "env:dev",
    "version:v1",
    "context-root!/api"
  ],
  "Check": {
    "HTTP": "http://10.0.1.5:8080/health",
    "Interval": "10s"
  }
}
```

## Deployment

### Server Setup (One-Time)

These steps are performed once by root to prepare a server for deployments.

**1. Create directory structure:**

```bash
# Create releases directory owned by the service user
mkdir -p /opt/zuul-consul-releases
chown zuul:zuul /opt/zuul-consul-releases
chmod 770 /opt/zuul-consul-releases

# Create permanent symlink (deploy script manages the 'current' symlink inside releases)
ln -sfn /opt/zuul-consul-releases/current /opt/zuul-consul
```

After deployment, the structure will be:
```
/opt/zuul-consul -> /opt/zuul-consul-releases/current  (permanent, created by root)
/opt/zuul-consul-releases/
├── current -> zuul-consul-4.1.3  (managed by deploy script)
├── zuul-consul-4.1.3/
│   ├── bin/zuul-consul
│   └── lib/*.jar
└── zuul-consul-4.1.2/  (previous version)
```

**2. Configure sudoers for service management:**

```bash
visudo -f /etc/sudoers.d/zuul-consul
```

Add:
```
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl restart zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl start zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl stop zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl status zuul-consul
%zuul ALL=(ALL) NOPASSWD: /bin/systemctl status zuul-consul --no-pager
```

**3. Create environment file:**

```bash
mkdir -p /etc/zuul-consul
chown zuul:zuul /etc/zuul-consul
chmod 770 /etc/zuul-consul
```

Create `/etc/zuul-consul/zuul-env.sh`:

```bash
ZUUL_CONSUL_AGENT_HOST=localhost
ZUUL_CONSUL_AGENT_PORT=8500
ZUUL_DEFAULT_ENVIRONMENT=dev
ZUUL_REACHABLE_ENVIRONMENTS='dev:int:uat:prod'
ZUUL_DEFAULT_TAGS='version:default'
ZUUL_CONSUL_TOKEN='<consul-acl-token>'
ZUUL_CONSUL_DATACENTER='jgi'
JAVA_OPTS='-Xms512m -Xmx1024m'
```

```bash
chmod 660 /etc/zuul-consul/zuul-env.sh  # protect the token
```

**Optional: Enable HTTPS**

To enable HTTPS, add SSL configuration to the environment file:

```bash
# SSL Configuration (PEM format)
ZUUL_SSL_CERT_PATH=/etc/zuul-consul/cert.pem
ZUUL_SSL_KEY_PATH=/etc/zuul-consul/key.pem
ZUUL_SERVER_PORT=8443
```

Copy your certificate files:
```bash
cp /path/to/your/cert.pem /etc/zuul-consul/
cp /path/to/your/key.pem /etc/zuul-consul/
chmod 640 /etc/zuul-consul/*.pem
chown zuul:zuul /etc/zuul-consul/*.pem
```

When SSL is enabled, the server defaults to port 8443. The nginx proxy_pass should use `https://` instead of `http://`.

**4. Create systemd service:**

Create `/etc/systemd/system/zuul-consul.service`:

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

### Jenkins Deployment

Deployments are triggered by pushing a git tag that matches the version in `gradle.properties`:

1. Update version in `gradle.properties`
2. Commit and push
3. Run `./tag.sh` to create and push the git tag
4. Jenkins pipeline builds, tests, and deploys through environments (dev → int → prd)
5. Integration and production deployments require manual approval in Jenkins

The deployment uses SSH to upload the distribution tar and update the `current` symlink.

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

Build and run:

```bash
./gradlew :app:fatJar
docker build -t zuul-consul .
docker run -p 9091:9091 \
  -e ZUUL_CONSUL_AGENT_HOST=consul.example.com \
  zuul-consul
```

### Behind NGINX

Running Zuul Consul behind an nginx reverse proxy is recommended for production deployments. This architecture provides several advantages:

#### Why Use NGINX in Front of Zuul Consul?

1. **SSL/TLS Termination** - Nginx efficiently handles SSL/TLS encryption, offloading this CPU-intensive work from the JVM. This simplifies certificate management and allows zuul-consul to focus on routing.

2. **Security Hardening** - Nginx provides additional security layers:
   - Rate limiting and connection limits
   - IP-based access control
   - Protection against slowloris and other attacks
   - Security headers (HSTS, X-Frame-Options, etc.)

3. **Performance Optimization** - Nginx excels at:
   - Static file serving (if needed)
   - Gzip compression
   - Connection pooling with keepalive
   - Buffering and caching

4. **Operational Benefits**:
   - Zero-downtime configuration reloads
   - Graceful restarts without dropping connections
   - Battle-tested stability under high load
   - Familiar tooling for operations teams

5. **Flexibility** - Nginx can:
   - Route specific paths to different backends
   - Serve health check endpoints independently
   - Handle CORS at the edge
   - Provide request/response logging

#### Reference Configuration

A complete reference nginx configuration is provided in `docker/nginx-proxy/` for use in the functional test environment. This configuration demonstrates all recommended settings and can be adapted for production use.

Key files:
- `docker/nginx-proxy/nginx.conf` - Main nginx configuration with performance tuning
- `docker/nginx-proxy/conf.d/zuul-consul.conf` - Proxy configuration with upstream, SSL, CORS, and security headers

To explore the configuration:

```bash
# View the main configuration
cat docker/nginx-proxy/nginx.conf

# View the proxy configuration
cat docker/nginx-proxy/conf.d/zuul-consul.conf
```

#### Quick Start Example

Minimal nginx configuration for running Zuul Consul behind a reverse proxy:

```nginx
upstream zuul_consul {
    server 127.0.0.1:9091;
    keepalive 32;
    keepalive_timeout 60s;
}

server {
    listen 443 ssl;
    server_name api.example.com;

    # SSL Configuration
    ssl_certificate /etc/ssl/certs/api.example.com.crt;
    ssl_certificate_key /etc/ssl/private/api.example.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    # Security Headers
    add_header Strict-Transport-Security "max-age=15768000" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;

    location / {
        proxy_pass http://zuul_consul;

        # HTTP/1.1 for keepalive
        proxy_http_version 1.1;
        proxy_set_header Connection "";

        # Forward client information
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Ssl on;

        # Timeouts
        proxy_connect_timeout 10s;
        proxy_read_timeout 60s;

        # Disable buffering for real-time responses
        proxy_buffering off;
    }
}
```

#### Production Recommendations

For production deployments, consider the additional settings demonstrated in `docker/nginx-proxy/conf.d/zuul-consul.conf`:

| Setting | Purpose |
|---------|---------|
| `upstream keepalive` | Connection pooling for better performance |
| `ssl_session_cache` | SSL session resumption for faster handshakes |
| `gzip on` | Compress responses to reduce bandwidth |
| `proxy_buffering off` | Real-time response streaming |
| CORS headers | Cross-origin request support |
| Security headers | HSTS, X-Frame-Options, etc. |

#### Testing Your NGINX Configuration

The functional test suite includes nginx-specific tests. To run them:

```bash
# Start the test environment with nginx
docker-compose -f docker/docker-compose.yml --profile nginx up -d

# Start zuul-consul
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
./gradlew :app:run &

# Run nginx tests
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
```

See `app/src/functionalTest/groovy/org/jadetipi/zuulconsul/functional/NginxProxySpec.groovy` for test examples covering:
- HTTP and HTTPS proxying
- CORS preflight handling
- Security header verification
- Routing through nginx

## Project Structure

```
zuul-consul/
├── app/                                    # Main application (Java)
│   └── src/main/java/org/jadetipi/zuulconsul/
│       ├── consul/                         # Consul client integration
│       │   ├── ConsulService.java          # Service instance wrapper
│       │   ├── ConsulServiceCache.java     # Thread-safe service cache
│       │   └── ConsulServiceRegistry.java  # Main Consul client
│       ├── discovery/                      # Zuul discovery integration
│       │   ├── ConsulDiscoveryResult.java  # Adapts to Zuul's discovery
│       │   └── ConsulServerResolver.java   # Server resolver for pools
│       ├── origins/                        # Origin management
│       │   ├── ConsulNettyOrigin.java      # Consul-based Netty origin
│       │   └── ConsulOriginManager.java    # Creates/manages origins
│       ├── server/                         # Server startup
│       │   ├── ConsulServerStartup.java    # Netty configuration
│       │   └── ZuulConsulServer.java       # Main entry point
│       └── service/
│           └── UriParser.java              # URI parsing utilities
├── filters/                                # Groovy filters
│   └── src/main/groovy/
│       ├── inbound/
│       │   └── ConsulRoutingFilter.groovy  # Request routing filter
│       └── outbound/
│           └── StatsFilter.groovy          # Response logging filter
├── build.gradle                            # Root build configuration
├── settings.gradle                         # Project settings
└── gradle.properties                       # Version properties
```

## Adding Custom Filters

Create Groovy filters in the `filters/src/main/groovy/` directory:

### Inbound Filter Example

```groovy
package inbound

import com.netflix.zuul.filters.http.HttpInboundSyncFilter
import com.netflix.zuul.message.http.HttpRequestMessage

class MyCustomFilter extends HttpInboundSyncFilter {

    @Override
    int filterOrder() {
        return 50  // Run before ConsulRoutingFilter (order 100)
    }

    @Override
    boolean shouldFilter(HttpRequestMessage request) {
        return true
    }

    @Override
    HttpRequestMessage apply(HttpRequestMessage request) {
        // Add custom header
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
    int filterOrder() {
        return 100
    }

    @Override
    boolean shouldFilter(HttpResponseMessage response) {
        return true
    }

    @Override
    HttpResponseMessage apply(HttpResponseMessage response) {
        // Add CORS headers
        response.getHeaders().set("Access-Control-Allow-Origin", "*")
        return response
    }
}
```

## Tracing Headers

Zuul Consul adds the following headers for request tracing:

| Header | Description |
|--------|-------------|
| `X-Zuul-Consul-Id` | Unique ID for this request |
| `X-Parent-Zuul-Consul-Id` | ID of the parent request (for nested calls) |
| `X-Root-Zuul-Consul-Id` | ID of the root request in the call chain |

## Health Check

The root path `/` returns service registry information when no service name is specified.

## License

Apache License 2.0
