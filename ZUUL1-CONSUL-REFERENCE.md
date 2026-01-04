# Zuul 1 Consul Project Reference

Reference notes for recreating zuul-consul functionality in Zuul 2.

## Git Repository

**Zuul 2 Project (this repo):**
- Repository: `git@github.com:duncanscott/zuul-consul.git`
- Local path: `/Users/duncanscott/git-hub/duncanscott/zuul-consul`
- Branches: `main`, `develop` (currently on `develop`)
- Upstream: `netflix` remote pointing to `https://github.com/Netflix/zuul.git`

## Build Tools

**Gradle:**
- Upgrade to Gradle 9.2.1 (default version on this Mac)
- Update `gradle/wrapper/gradle-wrapper.properties` to use 9.2.1

## Project Structure Approach

**Current repo contains full Netflix Zuul source (forked) - can be deleted.**

The original Zuul 1 zuul-consul imports Zuul as a library dependency:
```groovy
api 'com.netflix.zuul:zuul-core:1.3.1'
```

For Zuul 2, use the same approach - import as Maven dependencies:
```groovy
implementation 'com.netflix.zuul:zuul-core:3.0.8'       // Latest version
implementation 'com.netflix.zuul:zuul-discovery:3.0.8'  // Service discovery support
```

Available Zuul artifacts (Maven Central):
- `zuul-core` - Core gateway functionality
- `zuul-discovery` - Service discovery integration
- `zuul-groovy` - Groovy support (if needed)
- `zuul-guice` - Guice dependency injection

Sources:
- Maven: https://mvnrepository.com/artifact/com.netflix.zuul
- Getting Started: https://github.com/Netflix/zuul/wiki/Getting-Started-2.0

**Netflix Zuul source reference (after cleanup):**
- Location: `/Users/duncanscott/git-hub/netflix/zuul`
- Use this to reference Zuul 2 internals if needed after deleting code from this repo

**Original Zuul 1 Project:**
- Location: `/Users/duncanscott/git-code/pps/util/zuul-consul`

## Original Project Location
`/Users/duncanscott/git-code/pps/util/zuul-consul`

## Overview

A Zuul 1-based API gateway that integrates with HashiCorp Consul for dynamic service discovery. Deployed as a WAR file running in a servlet container (Tomcat).

## Architecture

### Zuul 1 Characteristics
- Servlet-based (HttpServletRequest/HttpServletResponse)
- Synchronous/blocking request handling
- Thread-per-request model
- Filters extend `com.netflix.zuul.ZuulFilter`
- Uses `RequestContext` (thread-local) for request state

### Filter Chain

```
Request → PRE filters → ROUTE filters → POST filters → Response
```

1. **PRE** (`ConsulRoutingFilter`, order 100): Parse URL, lookup service in Consul, set route host
2. **ROUTE** (`SimpleHostRoutingFilter`, order 100): Proxy request to backend via Apache HttpClient
3. **POST** (`SendResponseFilter`, order 1000): Write response back to client

## Key Components

### ConsulServiceRegistry
- Location: `app/src/main/groovy/doe/jgi/pi/pps/zuulconsul/consulservice/ConsulServiceRegistry.groovy`
- Manages Consul connection and service discovery
- Background reload every hour via TimerTask
- Configuration via environment variables:
  - `ZUUL_CONSUL_AGENT_HOST` - Consul agent (default: localhost)
  - `ZUUL_CONSUL_DATACENTER` - Required datacenter name
  - `ZUUL_CONSUL_TOKEN` - ACL token (optional)
  - `ZUUL_DEFAULT_ENVIRONMENT` - Default env (e.g., dev)
  - `ZUUL_REACHABLE_ENVIRONMENTS` - Colon-separated allowed envs
  - `ZUUL_DEFAULT_TAGS` - Default tags (format: `key:value/key2:value2`)

### ConsulService
- Location: `app/src/main/groovy/doe/jgi/pi/pps/zuulconsul/consulservice/ConsulService.groovy`
- Wraps Consul HealthService with URI and tag information
- Extracts context root from `context-root!` tag prefix
- Builds service URI from address, port, and context root

### UriParser
- Location: `app/src/main/groovy/doe/jgi/pi/pps/zuulconsul/service/UriParser.groovy`
- Parses incoming request URI to extract:
  - Tags (path elements containing `:`)
  - Service name (first path element without `:`)
  - Remaining path

### URL Pattern
```
GET /{tag1:value1}/{tag2:value2}/{serviceName}/{path}
```
Example:
```
GET /env:dev/version:v2/my-service/api/users/123
     ↑ tag    ↑ tag      ↑ service  ↑ path
```

### ConsulRoutingFilter (PRE)
- Location: `filters/src/main/groovy/pre/ConsulRoutingFilter.groovy`
- Parses request URI using UriParser
- Looks up service in ConsulServiceRegistry
- Sets `routeHost` and `requestURI` in RequestContext
- Generates tracing IDs (ZUUL_CONSUL_ID, PARENT_ZUUL_CONSUL_ID, ROOT_ZUUL_CONSUL_ID)
- Returns service registry JSON on root path `/`

### SimpleHostRoutingFilter (ROUTE)
- Location: `filters/src/main/groovy/route/SimpleHostRoutingFilter.groovy`
- Uses Apache HttpClient with connection pooling
- Forwards request to routeHost set by ConsulRoutingFilter
- Handles all HTTP methods (GET, POST, PUT, DELETE, OPTIONS, HEAD)
- Manages request/response headers

### SendResponseFilter (POST)
- Location: `filters/src/main/groovy/post/SendResponseFilter.groovy`
- Writes response from backend to client
- Handles gzip decompression if needed
- Captures response body for logging

## Dependencies (Zuul 1)

```groovy
api 'com.netflix.zuul:zuul-core:1.3.1'
api 'com.ecwid.consul:consul-api:1.4.5'
api 'org.apache.httpcomponents:httpclient:4.5.14'
api 'com.netflix.archaius:archaius-core:0.7.7'
```

## Consul Service Tags

Services in Consul use tags for routing:
- `env:dev` / `env:prd` - Environment
- `version:default` / `version:v2` - Version
- `context-root!/api/v1` - Context root path prefix
- `docs!https://docs.example.com` - Documentation URL

## Request Flow

1. Request arrives: `GET /env:dev/my-service/api/users`
2. `ConsulRoutingFilter.run()`:
   - Parse URI → service=`my-service`, tags=[`env:dev`], path=`/api/users`
   - Query Consul for healthy services matching name and tags
   - Select service instance (load balancer or first)
   - Build backend URI: `http://10.0.1.5:8080/api/users`
   - Set `ctx.setRouteHost(http://10.0.1.5:8080)`
   - Set `ctx.set("requestURI", "/api/users")`
3. `SimpleHostRoutingFilter.run()`:
   - Get routeHost from context
   - Forward request via HttpClient
   - Store response in context
4. `SendResponseFilter.run()`:
   - Write response headers and body to client

## Tracing Headers

Propagated through service calls:
- `X-Zuul-Consul-Id` - Unique ID for this gateway request
- `X-Parent-Zuul-Consul-Id` - Parent request ID (for nested calls)
- `X-Root-Zuul-Consul-Id` - Original root request ID

## Migration Considerations for Zuul 2

### Filter Type Mapping
| Zuul 1 | Zuul 2 |
|--------|--------|
| `filterType() = "pre"` | `HttpInboundSyncFilter` |
| `filterType() = "route"` | `ProxyEndpoint` (built-in) |
| `filterType() = "post"` | `HttpOutboundSyncFilter` |

### Key Differences
1. Replace `RequestContext` with `SessionContext` on message objects
2. Replace Apache HttpClient with Netty-based origin connections
3. Replace servlet request/response with `HttpRequestMessage`/`HttpResponseMessage`
4. Implement `DynamicServerResolver` for Consul integration instead of direct lookup
5. Use `OriginManager` for backend connection management

### Consul Integration Approach
- Create `ConsulServerResolver implements Resolver<DiscoveryResult>`
- Register with Zuul 2's origin framework
- Use existing ConsulServiceRegistry logic for service lookup

---

## Production Deployment Architecture

### Network Topology

```
Client
  ↓
ws-access-espint.jgi.lbl.gov (CNAME)
  ↓
ws-access.jgi.doe.gov (NGINX reverse proxy)
  ↓
zuul-01.jgi.lbl.gov:8443 (Zuul-Consul on Tomcat)
  ↓
Backend Services (discovered via Consul)
```

### NGINX Configuration
Location: `/Users/duncanscott/git-code/pps/system/nginx_ws-access/nginx.conf`

NGINX injects the environment tag into the URL path based on virtual host:

| Virtual Host | Zuul Instance | Path Rewrite |
|--------------|---------------|--------------|
| `ws-access-espint.jgi.lbl.gov` | zuul-01:8443 | `/` → `/env:espint/` |
| `ws-access-dev.jgi.doe.gov` | zuul-01:8443 | `/` → `/env:dev/` |
| `ws-access-int.jgi.doe.gov` | zuul-01:8443 | `/` → `/env:int/` |
| `ws-access.jgi.doe.gov` (prd) | zuul-02:8443 | No rewrite (uses default) |
| `ws-access-espprd.jgi.lbl.gov` | zuul-02:8443 | `/` → `/env:espprd/` |
| `ws-access-test.jgi.doe.gov` | zuul-03:8443 | `/` → `/env:dev/` |

**Special cases:**
- Root path `= /` passes through without env tag (returns service registry JSON)
- Hardcoded routes bypass Zuul: pps-email, pps-couchdb, pps-clarity-couch
- CORS headers added for browser clients
- 1-day proxy timeout (`proxy_read_timeout 1d`)

### Zuul Instances

| Instance | Environments | Notes |
|----------|--------------|-------|
| zuul-01.jgi.lbl.gov | dev, int, espint | Development/Integration |
| zuul-02.jgi.lbl.gov | prd, espprd | Production |
| zuul-03.jgi.lbl.gov | test | Testing |

### Tomcat Service Configuration

```ini
[Service]
Type=forking
User=svc-zuul@lbl.gov
Group=grp-svc-zuul@lbl.gov

Environment="JAVA_HOME=/usr/lib/jvm/jre"
Environment="JAVA_OPTS=-Djava.security.egd=file:///dev/urandom"
Environment="CATALINA_BASE=/usr/share/tomcat/active"
Environment="CATALINA_HOME=/usr/share/tomcat/active"
Environment="CATALINA_PID=/usr/share/tomcat/active/temp/tomcat.pid"
Environment="CATALINA_OPTS=-Xms5G -Xmx5G -server -XX:+UseParallelGC -javaagent:/home/svc-zuul__lbl.gov/elastic-apm/active.jar -Delastic.apm.service_name=zuul-consul -Delastic.apm.application_packages=doe"

EnvironmentFile=/home/svc-zuul__lbl.gov/zuul-env.sh

ExecStart=/usr/share/tomcat/active/bin/startup.sh
ExecStop=/usr/share/tomcat/active/bin/shutdown.sh
```

**Key settings:**
- 5GB heap size
- Parallel GC
- Elastic APM monitoring
- Environment vars loaded from `zuul-env.sh`

### Environment Variables (zuul-env.sh)

Location: `/home/svc-zuul__lbl.gov/zuul-env.sh`

```bash
ZUUL_DEFAULT_ENVIRONMENT='dev'
ZUUL_REACHABLE_ENVIRONMENTS='dev:int:uat:espint'
ZUUL_DEFAULT_TAGS='version:default'
ZUUL_CONSUL_TOKEN='<redacted>'
ZUUL_CONSUL_DATACENTER='jgi'
ROOT_LOG_LEVEL='info'
```

| Variable | Value | Purpose |
|----------|-------|---------|
| `ZUUL_DEFAULT_ENVIRONMENT` | `dev` | Default env when none specified in URL |
| `ZUUL_REACHABLE_ENVIRONMENTS` | `dev:int:uat:espint` | Colon-separated list of allowed envs |
| `ZUUL_DEFAULT_TAGS` | `version:default` | Default tags applied to service lookup |
| `ZUUL_CONSUL_TOKEN` | (ACL token) | Consul authentication |
| `ZUUL_CONSUL_DATACENTER` | `jgi` | Consul datacenter name |
| `ROOT_LOG_LEVEL` | `info` | Logging level |

**Note:** `ZUUL_CONSUL_AGENT_HOST` is not set, so it defaults to `localhost` (Consul agent runs locally on each Zuul server).

### Consul Cluster

Datacenter: `jgi`

**Server:**
- `consul-01.jgi.doe.gov` (128.3.122.18) - Consul server v1.20.1

**Clients (agents):**

| Node | IP | Purpose |
|------|-----|---------|
| zuul-01.jgi.lbl.gov | 128.3.96.184 | Zuul gateway (dev/int/espint) |
| zuul-02.jgi.lbl.gov | 128.3.96.185 | Zuul gateway (prd/espprd) |
| zuul-03.jgi.lbl.gov | 128.3.96.194 | Zuul gateway (test) |
| pps-dev.jgi.doe.gov | 128.3.71.15 | App server (dev) |
| pps-dev.jgi.lbl.gov | 128.3.96.95 | App server (dev) |
| pps-int.jgi.doe.gov | 131.243.27.221 | App server (int) |
| pps-int.jgi.lbl.gov | 128.3.96.96 | App server (int) |
| pps-prd.jgi.doe.gov | 128.3.122.57 | App server (prd) |
| pps-prd.jgi.lbl.gov | 128.3.96.97 | App server (prd) |
| couch-dev/int/prd | various | CouchDB servers |
| prospero, prospero-dev | various | Other infrastructure |

Each Zuul server has a local Consul agent that:
1. Connects to the Consul server cluster
2. Receives service catalog updates
3. Provides local API for service discovery (port 8500)
4. Uses `_agent` near parameter for proximity-based sorting

### Request Flow Example

Request: `https://ws-access-espint.jgi.lbl.gov/pps-esp-entity/version`

1. NGINX receives on `ws-access-espint.jgi.lbl.gov:443`
2. NGINX rewrites to `https://zuul-01.jgi.lbl.gov:8443/env:espint/pps-esp-entity/version`
3. Zuul-consul receives `/env:espint/pps-esp-entity/version`
4. UriParser extracts: tag=`env:espint`, service=`pps-esp-entity`, path=`/version`
5. ConsulServiceRegistry looks up healthy instances with matching tags
6. SimpleHostRoutingFilter proxies to backend
7. Response returns through the chain

### Live Instance for Testing
- URL: `https://ws-access-espint.jgi.lbl.gov/`
- Root path returns service registry JSON
- Example: `https://ws-access-espint.jgi.lbl.gov/pps-esp-entity/version`
- Requires VPN access

### Zuul 2 Deployment Changes

When migrating to Zuul 2:
- **No Tomcat** - Netty-based, runs as standalone JAR
- **Startup change**: `java -jar zuul-consul.jar` instead of Tomcat
- **Same env vars** - `zuul-env.sh` can still be used
- **Same APM** - Elastic APM agent works the same way

Example systemd service for Zuul 2:
```ini
ExecStart=/usr/bin/java ${JAVA_OPTS} ${CATALINA_OPTS} -jar /path/to/zuul-consul.jar
```

---

## Zuul 2 Design Decisions

### Hybrid Java/Groovy Architecture

| Layer | Language | Rationale |
|-------|----------|-----------|
| Core framework | Java | Zuul 2 core, Netty integration |
| Consul integration | Java or Groovy | ConsulServiceRegistry, resolver |
| Filters | Groovy | Familiar pattern, concise syntax |
| Utilities | Groovy | OnDemandCache, UriParser |
| Configuration | Java | Server startup, dependency injection |

**Note:** Unlike Zuul 1, Zuul 2 does not have runtime Groovy compilation by default. Groovy filters are pre-compiled during build.

### Developer Experience for Filters

**Requirement:** Make it easy for developers to quickly add simple Groovy filters (e.g., log request bodies to CouchDB for testing).

**Design goal:** A developer should be able to:
1. Create a new `.groovy` file extending the appropriate filter base class
2. Implement `filterOrder()`, `shouldFilter()`, and `apply()`
3. Deploy with minimal friction

**Options to support this:**
1. **Restore dynamic Groovy loading** - Re-implement GroovyCompiler for Zuul 2, load filters from directory at runtime
2. **Separate filters module** - Quick rebuild of just the filters subproject
3. **Hot reload during development** - Use dev tools for automatic restart on changes

**Example simple filter (target experience):**
```groovy
class LogRequestToCouch extends HttpInboundSyncFilter {
    int filterOrder() { 50 }
    boolean shouldFilter(HttpRequestMessage request) { true }

    HttpRequestMessage apply(HttpRequestMessage request) {
        // Save request body to CouchDB for testing team
        couchClient.save(request.body)
        return request
    }
}
```

### Filter Organization (Zuul 1)

**Directory Structure:**
```
filters/                          # Separate Gradle subproject
└── src/main/groovy/
    ├── pre/                      # PRE filters (before routing)
    │   ├── ConsulRoutingFilter.groovy   (order 100) - main routing logic
    │   ├── DebugFilter.groovy           (order 1)   - debug mode setup
    │   └── DebugRequest.groovy                      - request debugging
    ├── route/                    # ROUTE filters (proxy to backend)
    │   └── SimpleHostRoutingFilter.groovy (order 100) - HTTP proxying
    └── post/                     # POST filters (after response)
        ├── SendResponseFilter.groovy    (order 1000) - write response to client
        └── Stats.groovy                 (order 2000) - logging/metrics
```

**Key Design Decisions:**
1. **Directory-based organization** - Filters grouped by type (pre/route/post)
2. **Package matches directory** - `package pre`, `package route`, `package post`
3. **Flat structure** - No nested subpackages, easy to find filters
4. **Separate subproject** - `filters/` is independent from `app/`, allows quick rebuilds
5. **Clear ordering** - Lower numbers run first within each type

**Filter Execution Order:**
```
PRE:   DebugFilter(1) → ConsulRoutingFilter(100)
ROUTE: SimpleHostRoutingFilter(100)
POST:  SendResponseFilter(1000) → Stats(2000)
```

**Stats Filter (order 2000):**
- Runs last to capture final request state
- Uses SLF4J MDC for structured logging
- Logs Elastic-compatible fields (ECS format)
- Captures: timing, service info, tracing IDs, response status
- Extracts team from service name prefix (e.g., `pps-` → `pps`)

**Recommendation for Zuul 2:**
- Preserve directory-based organization (inbound/endpoint/outbound)
- Keep filters as separate subproject for easy customization
- Maintain Stats-like logging filter for observability
- Consider: `filters/src/main/groovy/{inbound,endpoint,outbound}/`

### Consul Client Library Decision

**Decision: Use Vert.x Consul Client for Zuul 2**

**Why not Ecwid/consul-api (current library):**
- Abandoned - no releases since April 2020
- Does not work with Consul 1.17+ (token passed as query param, not header)
- PR #245 with fix has been approved but never merged
- Project appears abandoned

**Why Vert.x Consul Client:**
- Actively maintained (releases through Oct 2024)
- Works with modern Consul versions
- Async/reactive model matches Zuul 2's Netty architecture
- Proper token header support
- Part of well-maintained Vert.x ecosystem

**Alternatives considered:**
| Library | Status | Decision |
|---------|--------|----------|
| Ecwid/consul-api | Abandoned | ❌ Current, but broken with Consul 1.17+ |
| rickfast/consul-client | Archived | ❌ Maintainer only merges PRs |
| Fork Ecwid + patch | Possible | ❌ We'd maintain a fork forever |
| Spring Cloud Consul | Active | ❌ Uses Ecwid under the hood |
| Write minimal client | Possible | ❌ More work, but viable fallback |
| **Vert.x Consul** | **Active** | ✅ **Selected** |

**Migration impact:**
- ConsulServiceRegistry will need to be rewritten to use Vert.x APIs
- Vert.x client is async (returns `Future<T>`) - fits Zuul 2 model
- Health service queries, catalog queries work similarly
- Token passed via header (Consul 1.17+ compatible)

**Resources:**
- Docs: https://vertx.io/docs/vertx-consul-client/java/
- Maven: `io.vertx:vertx-consul-client`
- GitHub: https://github.com/vert-x3/vertx-consul-client
- Local clone: `/Users/duncanscott/git-hub/vert-x3/vertx-consul-client`

### Vert.x Consul Client API Reference

**Creating client:**
```java
ConsulClientOptions options = new ConsulClientOptions()
    .setHost("localhost")           // default
    .setPort(8500)                  // default
    .setDc("jgi")                   // datacenter
    .setAclToken("token-uuid");     // ACL token (passed via header, not query param!)

ConsulClient client = ConsulClient.create(vertx, options);
```

**Querying healthy services (equivalent to current ConsulServiceRegistry):**
```java
// Query healthy instances of a service with tag filtering
ServiceQueryOptions queryOptions = new ServiceQueryOptions()
    .setTag("env:espint")           // Filter by single tag
    .setNear("_agent");             // Sort by proximity to local agent

client.healthServiceNodes("pps-esp-entity", true, queryOptions)
    .onSuccess(serviceEntryList -> {
        for (ServiceEntry entry : serviceEntryList.getList()) {
            Service service = entry.getService();
            String address = service.getAddress();  // or getNodeAddress()
            int port = service.getPort();
            List<String> tags = service.getTags();  // e.g., ["env:espint", "version:default", "context-root!/api"]
        }
    });
```

**Key classes:**
- `ConsulClient` - Main interface (async, returns `Future<T>`)
- `ConsulClientOptions` - Configuration (host, port, dc, aclToken)
- `ServiceQueryOptions` - Query options (tag, near, blocking)
- `ServiceEntry` - Contains Node, Service, and Checks
- `Service` - Service properties (name, address, port, tags, meta)
- `Node` - Node properties (name, address)

**Mapping from Ecwid to Vert.x:**
| Ecwid API | Vert.x API |
|-----------|------------|
| `HealthServicesRequest.setTag()` | `ServiceQueryOptions.setTag()` |
| `HealthServicesRequest.setNear(AGENT)` | `ServiceQueryOptions.setNear("_agent")` |
| `consulClient.getHealthServices()` | `consulClient.healthServiceNodes()` |
| `HealthService.getService()` | `ServiceEntry.getService()` |
| `Service.getAddress()` | `Service.getAddress()` |
| `Service.getPort()` | `Service.getPort()` |
| `Service.getTags()` | `Service.getTags()` |

**Note:** Vert.x `ServiceQueryOptions.setTag()` only supports a single tag. For multiple tag filtering (like `env:espint AND version:default`), we may need to:
1. Filter in application code after query
2. Use prepared queries
3. Make multiple queries

### Caching Strategy

Use improved OnDemandCache from groovy-utils project:
- Location: `/Users/duncanscott/git-code/pps/libraries/groovy-utils/libraries/on-demand-cache`
- Key improvements over original:
  - Proper double-checked locking with `volatile`
  - NULL_SENTINEL pattern for caching nulls
  - `computeIfAbsent()` for atomic map operations
- Alternative: Caffeine cache for more complex scenarios
