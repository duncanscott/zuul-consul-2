package inbound

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.filters.http.HttpInboundSyncFilter
import com.netflix.zuul.message.http.HttpRequestMessage
import com.netflix.zuul.filters.endpoint.ProxyEndpoint
import com.netflix.zuul.origins.BasicNettyOrigin
import groovy.util.logging.Slf4j

/**
 * Inbound filter that parses the request URI and routes to Consul-discovered services.
 * <p>
 * This filter:
 * <ul>
 *   <li>Parses the incoming URI to extract service name, tags, and remaining path</li>
 *   <li>Looks up the service in Consul</li>
 *   <li>Sets the route host and path for the proxy endpoint</li>
 *   <li>Propagates tracing headers</li>
 * </ul>
 * <p>
 * URI Pattern: /{tag:value}/.../{serviceName}/{path}
 * <p>
 * Example: /env:dev/my-service/api/users
 *   - tags: [env:dev]
 *   - serviceName: my-service
 *   - path: /api/users
 */
@Slf4j
class ConsulRoutingFilter extends HttpInboundSyncFilter {

    // Context keys for routing information
    static final String CONSUL_SERVICE_NAME = 'consul.serviceName'
    static final String CONSUL_SERVICE_TAGS = 'consul.serviceTags'
    static final String CONSUL_SERVICE_URI = 'consul.serviceUri'
    static final String CONSUL_REQUEST_PATH = 'consul.requestPath'
    static final String CONSUL_ORIGINAL_URI = 'consul.originalUri'
    static final String CONSUL_START_NANO = 'consul.startNano'

    // W3C Trace Context headers
    static final String HEADER_TRACEPARENT = 'traceparent'
    static final String HEADER_TRACESTATE = 'tracestate'

    // Legacy tracing header names (kept for backward compatibility)
    static final String HEADER_ZUUL_CONSUL_ID = 'X-Zuul-Consul-Id'
    static final String HEADER_PARENT_ZUUL_CONSUL_ID = 'X-Parent-Zuul-Consul-Id'
    static final String HEADER_ROOT_ZUUL_CONSUL_ID = 'X-Root-Zuul-Consul-Id'

    private static final ObjectMapper objectMapper = new ObjectMapper()

    @Override
    int filterOrder() {
        return 100
    }

    @Override
    boolean shouldFilter(HttpRequestMessage request) {
        return true
    }

    @Override
    HttpRequestMessage apply(HttpRequestMessage request) {
        SessionContext context = request.getContext()
        String uri = request.getPath()
        String queryString = request.getQueryParams()?.toEncodedString()

        log.debug("ConsulRoutingFilter processing: {}", uri)

        // Parse the URI
        ParseResult parsed = parseUri(uri)

        // Store parsed info in context for downstream use
        context.set(CONSUL_ORIGINAL_URI, uri)
        context.set(CONSUL_SERVICE_NAME, parsed.serviceName)
        context.set(CONSUL_SERVICE_TAGS, parsed.tags)
        String remainingPath = parsed.remainingPath ?: '/'
        request.setPath(remainingPath)
        context.set(CONSUL_REQUEST_PATH, remainingPath)
        if (!context.containsKey(CONSUL_START_NANO)) {
            context.set(CONSUL_START_NANO, System.nanoTime())
        }

        // Handle root path - return service registry info
        if (parsed.isRootPath()) {
            log.debug("Root path request - will return service registry")
            context.setEndpoint('org.jadetipi.zuulconsul.endpoints.ServiceRegistryEndpoint')
            return request
        }

        // Add tracing headers
        addTracingHeaders(request)

        // Route to proxy endpoint - the actual Consul lookup happens in the origin
        // Uses Zuul's PROXY_ENDPOINT_FILTER_NAME which is ProxyEndpoint.class.getCanonicalName()
        context.setEndpoint(ProxyEndpoint.class.getCanonicalName())
        context.setRouteVIP(parsed.serviceName)

        // Store full path with query string for the backend request
        String backendPath = remainingPath
        if (queryString != null && !queryString.isEmpty()) {
            backendPath = backendPath + '?' + queryString
        }
        context.set(CONSUL_REQUEST_PATH, backendPath)

        log.debug("Routing to service: {}, tags: {}, path: {}",
            parsed.serviceName, parsed.tags, backendPath)

        return request
    }

    /**
     * Add tracing headers for request correlation.
     * <p>
     * Sets W3C Trace Context headers (traceparent) for distributed tracing,
     * plus legacy X-Zuul-Consul-* headers for backward compatibility.
     */
    private void addTracingHeaders(HttpRequestMessage request) {
        def context = request.getContext()

        // Get trace context from RequestIdFilter
        String traceId = context.get(RequestIdFilter.TRACE_ID) as String
        String spanId = context.get(RequestIdFilter.SPAN_ID) as String
        String traceFlags = context.get(RequestIdFilter.TRACE_FLAGS) as String ?: '01'

        // Fallback if RequestIdFilter didn't run (shouldn't happen)
        if (!traceId) {
            traceId = RequestIdFilter.generateTraceId()
            spanId = RequestIdFilter.generateSpanId()
        }

        // Set W3C traceparent header for downstream services
        String traceparent = RequestIdFilter.buildTraceparent(traceId, spanId, traceFlags)
        request.getHeaders().set(HEADER_TRACEPARENT, traceparent)

        // Propagate tracestate if present
        String incomingTracestate = request.getHeaders().getFirst(HEADER_TRACESTATE)
        if (incomingTracestate) {
            request.getHeaders().set(HEADER_TRACESTATE, incomingTracestate)
        }

        // Legacy headers for backward compatibility
        request.getHeaders().set(HEADER_ZUUL_CONSUL_ID, traceId)

        // Check if this is a nested request (coming through another Zuul instance)
        String incomingId = request.getHeaders().getFirst(HEADER_ZUUL_CONSUL_ID)
        String incomingRootId = request.getHeaders().getFirst(HEADER_ROOT_ZUUL_CONSUL_ID)

        if (incomingId != null && incomingId != traceId) {
            request.getHeaders().set(HEADER_PARENT_ZUUL_CONSUL_ID, incomingId)
        }

        if (incomingRootId != null) {
            request.getHeaders().set(HEADER_ROOT_ZUUL_CONSUL_ID, incomingRootId)
        } else {
            request.getHeaders().set(HEADER_ROOT_ZUUL_CONSUL_ID, traceId)
        }

        log.trace("Trace headers set: traceparent={}", traceparent)
    }

    /**
     * Parse a URI into service name, tags, and remaining path.
     */
    private ParseResult parseUri(String uri) {
        if (uri == null || uri.isEmpty() || uri == '/') {
            return new ParseResult(null, [], '/')
        }

        // Remove leading slash and split
        String normalized = uri.startsWith('/') ? uri.substring(1) : uri
        String[] parts = normalized.split('/')

        if (parts.length == 0) {
            return new ParseResult(null, [], '/')
        }

        List<String> tags = []
        String serviceName = null
        int serviceIndex = -1

        // Tags contain ":", service name does not
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i]
            if (part.isEmpty()) continue

            if (part.contains(':')) {
                tags.add(part)
            } else {
                serviceName = part
                serviceIndex = i
                break
            }
        }

        // Build remaining path
        String remainingPath = '/'
        if (serviceIndex >= 0 && serviceIndex < parts.length - 1) {
            StringBuilder sb = new StringBuilder()
            for (int i = serviceIndex + 1; i < parts.length; i++) {
                sb.append('/').append(parts[i])
            }
            remainingPath = sb.toString()
        }

        return new ParseResult(serviceName, tags, remainingPath)
    }

    /**
     * Result of parsing a URI.
     */
    private static class ParseResult {
        final String serviceName
        final List<String> tags
        final String remainingPath

        ParseResult(String serviceName, List<String> tags, String remainingPath) {
            this.serviceName = serviceName
            this.tags = tags ?: []
            this.remainingPath = remainingPath ?: '/'
        }

        boolean isRootPath() {
            return serviceName == null || serviceName.isEmpty()
        }
    }
}
