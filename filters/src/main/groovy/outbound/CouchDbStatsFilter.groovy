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
 * Field naming follows Elastic Common Schema (ECS) and OpenTelemetry conventions.
 * The document _id is set to the trace.id for easy lookup.
 *
 * @see <a href="https://www.elastic.co/guide/en/ecs/current/ecs-field-reference.html">ECS Field Reference</a>
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">OTel HTTP Semantic Conventions</a>
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

        // Get trace context
        String traceId = context.get(RequestIdFilter.TRACE_ID) as String
        String spanId = context.get(RequestIdFilter.SPAN_ID) as String

        // Generate fallback IDs if not available
        if (!traceId) {
            traceId = UUID.randomUUID().toString().replace('-', '')
        }
        if (!spanId) {
            spanId = UUID.randomUUID().toString().replace('-', '').substring(0, 16)
        }

        // Build the document
        Map<String, Object> doc = buildDocument(context, response, traceId, spanId)
        String json = objectMapper.writeValueAsString(doc)

        // Use PUT with span ID as document _id (unique per operation)
        String docUrl = "${couchDbUrl}/${spanId}"

        // Build the request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(docUrl))
            .timeout(Duration.ofSeconds(10))
            .header('Content-Type', 'application/json')
            .PUT(HttpRequest.BodyPublishers.ofString(json))

        if (authHeader) {
            requestBuilder.header('Authorization', authHeader)
        }

        HttpRequest request = requestBuilder.build()

        // Send async to avoid blocking the event loop
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept { resp ->
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.trace("Posted stats to CouchDB: {} -> {}", spanId, resp.statusCode())
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
     * Field naming follows Elastic Common Schema (ECS) and OpenTelemetry conventions.
     * Fields use dots (.) as separators per ECS conventions.
     * <p>
     * The document _id is set to span.id (unique per operation) rather than trace.id
     * (which is shared across all spans in a distributed trace).
     *
     * @see <a href="https://www.elastic.co/guide/en/ecs/current/ecs-field-reference.html">ECS Field Reference</a>
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">OTel HTTP Semantic Conventions</a>
     */
    private Map<String, Object> buildDocument(SessionContext context, HttpResponseMessage response, String traceId, String spanId) {
        Map<String, Object> doc = new LinkedHashMap<>()

        // Document metadata - use span.id as _id (unique per operation)
        doc.put('_id', spanId)

        // Timestamp (ECS standard field)
        doc.put('@timestamp', ISO_FORMATTER.format(Instant.now()))
        doc.put('type', 'request_stats')

        // Trace context (ECS trace fields)
        doc.put('trace.id', traceId)
        doc.put('span.id', spanId)

        // Service info (ECS service fields)
        String serviceName = context.get(CONSUL_SERVICE_NAME) as String
        if (serviceName) {
            doc.put('service.name', serviceName)
            // Extract team prefix as label
            if (serviceName.contains('-')) {
                doc.put('labels.team', serviceName.split('-')[0])
            }
        }

        // Service URI components (OTel and ECS conventions)
        String serviceUri = context.get(CONSUL_SERVICE_URI) as String
        if (serviceUri) {
            doc.put('url.full', serviceUri)
            try {
                URI uri = new URI(serviceUri)
                if (uri.host) {
                    doc.put('server.address', uri.host)
                }
                if (uri.port > 0) {
                    doc.put('server.port', uri.port)
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        // Request info (ECS HTTP fields)
        def inboundRequest = response.getInboundRequest()
        if (inboundRequest) {
            doc.put('http.request.method', inboundRequest.getMethod() ?: 'unknown')
            doc.put('url.original', context.get(CONSUL_ORIGINAL_URI) as String ?: inboundRequest.getPath())
        }
        doc.put('url.path', context.get(CONSUL_REQUEST_PATH) as String ?: '/')

        // Response info (ECS HTTP fields)
        doc.put('http.response.status_code', response.getStatus())

        // Duration in milliseconds (custom field, keeping for human readability)
        Long startNano = context.get(CONSUL_START_NANO) as Long
        if (startNano) {
            long durationMs = Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L)
            doc.put('event.duration', durationMs)
        }

        // Dynamic tags from URI as labels (ECS labels field)
        List<String> tags = context.get(CONSUL_SERVICE_TAGS) as List<String>
        if (tags) {
            tags.each { String tag ->
                if (tag?.contains(':')) {
                    String[] parts = tag.split(':', 2)
                    if (parts.length == 2) {
                        doc.put("labels.${parts[0]}", parts[1])
                    }
                }
            }
        }

        // X-Forwarded-For (ECS client fields)
        String forwardedFor = inboundRequest?.getHeaders()?.getFirst('X-Forwarded-For')
        if (forwardedFor) {
            String clientIp = forwardedFor.split(',')[0].trim()
            doc.put('client.ip', clientIp)
            doc.put('client.address', clientIp)  // OTel semantic convention
        }

        // Event outcome (ECS event fields)
        if (response.getStatus() >= 400) {
            doc.put('event.outcome', 'failure')
            if (response.getStatus() >= 500) {
                doc.put('error.type', 'server_error')
            } else {
                doc.put('error.type', 'client_error')
            }
        } else {
            doc.put('event.outcome', 'success')
        }

        // Request body as JSON object (ECS http.request.body.content)
        String requestBody = context.get(BodyBufferFilter.REQUEST_BODY_KEY) as String
        if (requestBody) {
            Object parsedBody = parseJsonContent(requestBody)
            if (parsedBody != null) {
                doc.put('http.request.body.content', parsedBody)
            }
        }

        // Response body as JSON object (ECS http.response.body.content)
        try {
            String responseContentType = response.getHeaders()?.getFirst('Content-Type')
            if (responseContentType?.toLowerCase()?.contains('application/json')) {
                if (response.hasCompleteBody()) {
                    byte[] bodyBytes = response.getBody()
                    if (bodyBytes != null && bodyBytes.length > 0 && bodyBytes.length <= MAX_BODY_SIZE) {
                        String responseBody = new String(bodyBytes, 'UTF-8')
                        Object parsedBody = parseJsonContent(responseBody)
                        if (parsedBody != null) {
                            doc.put('http.response.body.content', parsedBody)
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
     * Parse a JSON string into a Map or List for storage as a JSON object.
     * Returns null if not valid JSON.
     */
    private static Object parseJsonContent(String str) {
        if (str == null || str.isEmpty()) {
            return null
        }
        String trimmed = str.trim()
        // Quick check: must start with { or [
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
            return null
        }
        try {
            // Parse as generic Object (will be Map or List)
            return objectMapper.readValue(str, Object.class)
        } catch (Exception e) {
            return null
        }
    }
}
