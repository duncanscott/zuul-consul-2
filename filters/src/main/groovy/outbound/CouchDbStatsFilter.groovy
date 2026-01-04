package outbound

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.filters.http.HttpOutboundSyncFilter
import com.netflix.zuul.message.http.HttpResponseMessage
import groovy.util.logging.Slf4j
import inbound.BodyBufferFilter
import inbound.RequestIdFilter

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Outbound filter that posts request statistics to CouchDB.
 * <p>
 * This filter is disabled by default. Enable it by setting:
 * <pre>
 * ZUUL_COUCHDB_ENABLED=true
 * ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
 * ZUUL_COUCHDB_USER=admin
 * ZUUL_COUCHDB_PASSWORD=password
 * </pre>
 * <p>
 * The filter posts a JSON document for each request containing:
 * <ul>
 *   <li>timestamp - ISO8601 formatted timestamp</li>
 *   <li>trace_id - W3C trace ID</li>
 *   <li>span_id - W3C span ID</li>
 *   <li>service - target service name</li>
 *   <li>method - HTTP method</li>
 *   <li>status - HTTP response status code</li>
 *   <li>path - request path</li>
 *   <li>duration_ms - request duration in milliseconds</li>
 * </ul>
 */
@Slf4j
class CouchDbStatsFilter extends HttpOutboundSyncFilter {

    // Environment variable names
    static final String ENV_ENABLED = 'ZUUL_COUCHDB_ENABLED'
    static final String ENV_URL = 'ZUUL_COUCHDB_URL'
    static final String ENV_USER = 'ZUUL_COUCHDB_USER'
    static final String ENV_PASSWORD = 'ZUUL_COUCHDB_PASSWORD'

    // Context keys (from other filters)
    static final String CONSUL_SERVICE_NAME = 'consul.serviceName'
    static final String CONSUL_SERVICE_TAGS = 'consul.serviceTags'
    static final String CONSUL_SERVICE_URI = 'consul.serviceUri'
    static final String CONSUL_REQUEST_PATH = 'consul.requestPath'
    static final String CONSUL_ORIGINAL_URI = 'consul.originalUri'
    static final String CONSUL_START_NANO = 'consul.startNano'

    private static final ObjectMapper objectMapper = new ObjectMapper()
    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    // Configuration (loaded once at startup)
    private final boolean enabled
    private final String couchDbUrl
    private final String authHeader
    private final HttpClient httpClient

    CouchDbStatsFilter() {
        this.enabled = 'true'.equalsIgnoreCase(System.getenv(ENV_ENABLED))
        this.couchDbUrl = System.getenv(ENV_URL)

        String user = System.getenv(ENV_USER)
        String password = System.getenv(ENV_PASSWORD)

        if (enabled && user && password) {
            String credentials = "${user}:${password}"
            this.authHeader = 'Basic ' + Base64.getEncoder().encodeToString(credentials.getBytes('UTF-8'))
        } else {
            this.authHeader = null
        }

        if (enabled) {
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            log.info("CouchDbStatsFilter enabled, posting to: {}", couchDbUrl)
        } else {
            this.httpClient = null
            log.debug("CouchDbStatsFilter disabled (set {}=true to enable)", ENV_ENABLED)
        }
    }

    @Override
    int filterOrder() {
        return 2100  // Run after StatsFilter (2000)
    }

    @Override
    boolean shouldFilter(HttpResponseMessage response) {
        return enabled && couchDbUrl
    }

    @Override
    HttpResponseMessage apply(HttpResponseMessage response) {
        try {
            postToCouchDb(response)
        } catch (Exception e) {
            log.warn("Failed to post stats to CouchDB: {}", e.message)
        }
        return response
    }

    private void postToCouchDb(HttpResponseMessage response) {
        SessionContext context = response.getContext()

        // Build the document
        Map<String, Object> doc = buildDocument(context, response)
        String json = objectMapper.writeValueAsString(doc)

        // Build the request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(couchDbUrl))
            .timeout(Duration.ofSeconds(10))
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(json))

        if (authHeader) {
            requestBuilder.header('Authorization', authHeader)
        }

        HttpRequest request = requestBuilder.build()

        // Send async to avoid blocking the event loop
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { resp ->
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.trace("Posted stats to CouchDB: {}", resp.statusCode())
                } else {
                    log.warn("CouchDB returned status {}: {}", resp.statusCode(), resp.body())
                }
            }
            .exceptionally { throwable ->
                log.warn("Failed to post to CouchDB: {}", throwable.message)
                return null
            }
    }

    /**
     * Build the CouchDB document with all request stats.
     * <p>
     * Field naming follows the original zuul-consul Stats.groovy for consistency
     * with existing log parsing. For future additions, consider Elastic Common
     * Schema (ECS) conventions.
     *
     * @see <a href="https://www.elastic.co/guide/en/ecs/current/ecs-field-reference.html">ECS Field Reference</a>
     */
    private Map<String, Object> buildDocument(SessionContext context, HttpResponseMessage response) {
        Map<String, Object> doc = new LinkedHashMap<>()

        // Timestamp
        doc.put('timestamp', ISO_FORMATTER.format(Instant.now()))
        doc.put('type', 'request_stats')

        // Trace context (using old zuul-consul field names for compatibility)
        String traceId = context.get(RequestIdFilter.TRACE_ID) as String
        String spanId = context.get(RequestIdFilter.SPAN_ID) as String
        String parentId = context.get(RequestIdFilter.PARENT_ID) as String
        String rootId = context.get(RequestIdFilter.ROOT_ID) as String

        if (traceId) {
            doc.put('zuul_consul_id', traceId)
        }
        if (spanId) {
            doc.put('span_id', spanId)
        }
        if (parentId) {
            doc.put('zuul_consul_parent_id', parentId)
        }
        if (rootId) {
            doc.put('zuul_consul_root_id', rootId)
        }

        // Service info
        String serviceName = context.get(CONSUL_SERVICE_NAME) as String
        if (serviceName) {
            doc.put('service_name', serviceName)
            // Extract team prefix (fields.team in MDC)
            if (serviceName.contains('-')) {
                doc.put('team', serviceName.split('-')[0])
            }
        }

        // Service URI components (matching old Stats.groovy field names)
        String serviceUri = context.get(CONSUL_SERVICE_URI) as String
        if (serviceUri) {
            doc.put('service_uri', serviceUri)
            try {
                URI uri = new URI(serviceUri)
                if (uri.host) {
                    doc.put('service_host_name', uri.host)
                    doc.put('server_address', uri.host)  // OTel semantic convention
                }
                if (uri.port > 0) {
                    doc.put('service_port', uri.port)
                    doc.put('server_port', uri.port)  // OTel semantic convention
                }
                if (uri.path) {
                    doc.put('service_url_path', uri.path)
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        // Request info (ECS-compatible field names)
        def inboundRequest = response.getInboundRequest()
        if (inboundRequest) {
            doc.put('http_request_method', inboundRequest.getMethod() ?: 'unknown')
            doc.put('original_uri', context.get(CONSUL_ORIGINAL_URI) as String ?: inboundRequest.getPath())
        }
        doc.put('url_path', context.get(CONSUL_REQUEST_PATH) as String ?: '/')

        // Response info (ECS-compatible)
        doc.put('http_response_status_code', response.getStatus())

        // Duration (milliseconds to match old Stats.groovy)
        Long startNano = context.get(CONSUL_START_NANO) as Long
        if (startNano) {
            long durationMs = Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L)
            doc.put('milliseconds', durationMs)
        }

        // Dynamic tags from URI (e.g., env:dev, version:v1)
        List<String> tags = context.get(CONSUL_SERVICE_TAGS) as List<String>
        if (tags) {
            Map<String, String> tagMap = new LinkedHashMap<>()
            tags.each { String tag ->
                if (tag?.contains(':')) {
                    String[] parts = tag.split(':', 2)
                    if (parts.length == 2) {
                        tagMap.put(parts[0], parts[1])
                    }
                }
            }
            if (!tagMap.isEmpty()) {
                doc.put('tags', tagMap)
            }
        }

        // X-Forwarded-For
        String forwardedFor = inboundRequest?.getHeaders()?.getFirst('X-Forwarded-For')
        if (forwardedFor) {
            String clientIp = forwardedFor.split(',')[0].trim()
            doc.put('forwarded_for_ip', clientIp)
            doc.put('client_address', clientIp)  // OTel semantic convention
        }

        // Error info if applicable
        if (response.getStatus() >= 400) {
            doc.put('error', true)
            if (response.getStatus() >= 500) {
                doc.put('server_error', true)
            }
        }

        // Request body (if buffered by BodyBufferFilter and is JSON)
        // ECS field: http.request.body.content
        String requestBody = context.get(BodyBufferFilter.REQUEST_BODY_KEY) as String
        if (requestBody) {
            // Validate it's actually JSON before storing
            if (isValidJson(requestBody)) {
                doc.put('http_request_body_content', requestBody)
            }
        }

        // Response body (if JSON)
        // ECS field: http.response.body.content
        try {
            String responseContentType = response.getHeaders()?.getFirst('Content-Type')
            if (responseContentType?.toLowerCase()?.contains('application/json')) {
                if (response.hasCompleteBody()) {
                    byte[] bodyBytes = response.getBody()
                    if (bodyBytes != null && bodyBytes.length > 0 && bodyBytes.length <= MAX_BODY_SIZE) {
                        String responseBody = new String(bodyBytes, 'UTF-8')
                        if (isValidJson(responseBody)) {
                            doc.put('http_response_body_content', responseBody)
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Failed to capture response body: {}", e.message)
        }

        return doc
    }

    /**
     * Maximum body size to capture (1MB).
     * Larger bodies are skipped to avoid memory issues.
     */
    private static final int MAX_BODY_SIZE = 1024 * 1024

    /**
     * Check if a string is valid JSON.
     */
    private static boolean isValidJson(String str) {
        if (str == null || str.isEmpty()) {
            return false
        }
        String trimmed = str.trim()
        // Quick check: must start with { or [
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
            return false
        }
        try {
            objectMapper.readTree(str)
            return true
        } catch (Exception e) {
            return false
        }
    }
}
