# Zuul Consul 2.0

This document outlines the key features and changes in Zuul Consul 2.0, which is a complete rewrite based on Netflix Zuul 2's non-blocking architecture.

## Why Zuul Consul 2.0?

The original Zuul Consul was built on Zuul 1, which used a blocking, thread-per-request model based on Servlet APIs. While this worked well, it had limitations:

- **Thread exhaustion** under high load or slow backends
- **Blocking I/O** limited throughput
- **Servlet container dependency** (Tomcat)
- **Abandoned Consul client library** (Ecwid consul-api)

Zuul Consul 2.0 addresses these issues with a modern, non-blocking architecture.

## Key Changes from Zuul Consul 1.x

### Architecture

| Aspect | Zuul Consul 1.x | Zuul Consul 2.0 |
|--------|-----------------|-----------------|
| Base Framework | Zuul 1 (Servlet-based) | Zuul 2 (Netty-based) |
| I/O Model | Blocking (thread-per-request) | Non-blocking (async) |
| Server | Tomcat | Embedded Netty |
| Consul Client | Ecwid consul-api | Vert.x Consul Client |
| Language | Groovy | Java core + Groovy filters |
| Build Tool | Gradle (older) | Gradle 9.x |
| Java Version | Java 8 | Java 21 |

### Consul Client Library

**Before (Ecwid consul-api):**
- Last release: 2020
- Incompatible with Consul 1.17+ (token query parameter issue)
- Synchronous/blocking API
- No longer maintained

**After (Vert.x Consul Client):**
- Actively maintained
- Async/non-blocking API
- Compatible with modern Consul versions
- Native Vert.x Future support

### Filter Architecture

**Before (Zuul 1):**
```groovy
class MyFilter extends ZuulFilter {
    String filterType() { return "pre" }  // pre, route, post, error
    int filterOrder() { return 1 }
    boolean shouldFilter() { return true }
    Object run() {
        RequestContext ctx = RequestContext.getCurrentContext()
        // filter logic
    }
}
```

**After (Zuul 2):**
```groovy
class MyFilter extends HttpInboundSyncFilter {
    int filterOrder() { return 1 }
    boolean shouldFilter(HttpRequestMessage request) { return true }
    HttpRequestMessage apply(HttpRequestMessage request) {
        SessionContext ctx = request.getContext()
        // filter logic
        return request
    }
}
```

Key differences:
- Filter types are now class-based (`HttpInboundSyncFilter`, `HttpOutboundSyncFilter`, `HttpSyncEndpoint`)
- Request/response objects are passed explicitly (no thread-local)
- Filters return the modified message
- `SessionContext` replaces `RequestContext`

### Project Structure

**Before:**
```
zuul-consul/
├── src/main/groovy/
│   └── filters/
│       ├── pre/
│       ├── route/
│       └── post/
├── src/main/resources/
└── build.gradle
```

**After:**
```
zuul-consul/
├── app/                          # Java core application
│   └── src/main/java/
│       └── org/jadetipi/zuulconsul/
│           ├── consul/           # Consul integration
│           ├── discovery/        # Zuul discovery adapters
│           ├── origins/          # Origin management
│           ├── server/           # Server startup
│           └── service/          # Utilities
├── filters/                      # Groovy filters (separate module)
│   └── src/main/groovy/
│       ├── inbound/
│       └── outbound/
└── build.gradle
```

### Routing Mechanism

**Before:**
```groovy
// In route filter
RequestContext ctx = RequestContext.getCurrentContext()
ctx.setRouteHost(new URL("http://backend:8080"))
```

**After:**
```groovy
// In inbound filter
SessionContext ctx = request.getContext()
ctx.setEndpoint(ProxyEndpoint.class.getCanonicalName())
ctx.setRouteVIP("service-name")  // Resolved via ConsulOriginManager
```

The routing is now integrated with Zuul 2's origin and connection pool infrastructure.

### Service Discovery Integration

**Before:**
- Direct HTTP calls to Consul API
- Manual caching with `OnDemandCache`
- Synchronous service lookups
- Consul watches for real-time updates

**After:**
- Vert.x Consul Client with async API
- `ConsulServiceRegistry` with Consul watches for real-time updates
- Catalog watch detects new/removed services
- Per-service watches detect instance changes
- Fallback periodic refresh for reliability
- `ConsulServerResolver` integrates with Zuul's `Resolver` interface
- `ConsulOriginManager` creates connection pools per service

### Connection Pooling

**Before:**
- No connection pooling (new connection per request)
- Or basic HTTP client pooling

**After:**
- Netty-based connection pools per origin
- Configurable pool sizes and timeouts
- Health-aware connection management
- Automatic retry with next server

## New Features in 2.0

### 1. Non-Blocking I/O
All I/O operations are non-blocking, allowing the server to handle many more concurrent connections with fewer threads.

### 2. Improved Metrics
Integration with Netflix Spectator for comprehensive metrics:
- Connection pool stats
- Request latencies
- Error rates per origin

### 3. Better Error Handling
Structured error types with `StatusCategory` for consistent error reporting and monitoring.

### 4. Request Tracing
Built-in tracing headers for distributed request tracking:
- `X-Zuul-Consul-Id`
- `X-Parent-Zuul-Consul-Id`
- `X-Root-Zuul-Consul-Id`

### 5. Graceful Shutdown
Proper connection draining and graceful shutdown support.

### 6. Modern Java
Built on Java 21 with modern language features and improved performance.

## Migration Guide

### Migrating Filters

1. **Change base class:**
   - `ZuulFilter` → `HttpInboundSyncFilter` or `HttpOutboundSyncFilter`

2. **Update filter type:**
   - Remove `filterType()` method
   - Use appropriate base class instead

3. **Update method signatures:**
   ```groovy
   // Before
   boolean shouldFilter() { ... }
   Object run() { ... }

   // After
   boolean shouldFilter(HttpRequestMessage request) { ... }
   HttpRequestMessage apply(HttpRequestMessage request) { ... }
   ```

4. **Update context access:**
   ```groovy
   // Before
   RequestContext ctx = RequestContext.getCurrentContext()
   HttpServletRequest request = ctx.getRequest()

   // After
   SessionContext ctx = request.getContext()
   // Access request directly from the message object
   String path = request.getPath()
   ```

5. **Update header access:**
   ```groovy
   // Before
   ctx.addZuulRequestHeader("X-Custom", "value")

   // After
   request.getHeaders().set("X-Custom", "value")
   ```

### Configuration Changes

| Old Property | New Property |
|--------------|--------------|
| `zuul.routes.*` | Use `SessionContext.setRouteVIP()` in filters |
| `ribbon.*` | `zuul.ribbon.*` |
| Server port (Tomcat) | `zuul.server.port.main` |

### Environment Variables

Environment variables remain largely the same:
- `ZUUL_CONSUL_AGENT_HOST`
- `ZUUL_CONSUL_AGENT_PORT`
- `ZUUL_CONSUL_DATACENTER`
- `ZUUL_CONSUL_TOKEN`
- `ZUUL_DEFAULT_ENVIRONMENT`
- `ZUUL_REACHABLE_ENVIRONMENTS`
- `ZUUL_DEFAULT_TAGS`

## Performance Improvements

Based on the architectural changes, Zuul Consul 2.0 provides:

- **Higher throughput** - Non-blocking I/O handles more concurrent requests
- **Lower latency** - No thread context switching overhead
- **Better resource utilization** - Fewer threads needed for same load
- **Improved stability** - No thread exhaustion under high load

## Compatibility Notes

### Breaking Changes

1. **Filter API** - All filters must be rewritten to use Zuul 2 APIs
2. **No Servlet API** - `HttpServletRequest`/`HttpServletResponse` not available
3. **No thread-local context** - Context passed explicitly in messages
4. **Different deployment** - Standalone JAR instead of WAR in Tomcat

### Preserved Functionality

1. **URL routing pattern** - Same `/{tag:value}/.../service/path` format
2. **Tag-based service discovery** - Same tag matching logic
3. **Environment variables** - Same configuration options
4. **Consul integration** - Same service registration expectations

## Future Enhancements

Planned improvements for future releases:

1. **Circuit Breaker** - Per-service circuit breaker integration
2. **Rate Limiting** - Built-in rate limiting filter
3. **WebSocket Support** - Proxy WebSocket connections
4. **gRPC Support** - Proxy gRPC traffic
5. **Admin API** - Runtime configuration and monitoring endpoints
