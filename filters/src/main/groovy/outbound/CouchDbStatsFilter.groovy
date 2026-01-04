package outbound

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.filters.http.HttpOutboundSyncFilter
import com.netflix.zuul.message.http.HttpResponseMessage
import groovy.util.logging.Slf4j
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

    private Map<String, Object> buildDocument(SessionContext context, HttpResponseMessage response) {
        Map<String, Object> doc = new LinkedHashMap<>()

        // Timestamp
        doc.put('timestamp', ISO_FORMATTER.format(Instant.now()))
        doc.put('type', 'request_stats')

        // Trace context
        String traceId = context.get(RequestIdFilter.TRACE_ID) as String
        String spanId = context.get(RequestIdFilter.SPAN_ID) as String
        if (traceId) {
            doc.put('trace_id', traceId)
        }
        if (spanId) {
            doc.put('span_id', spanId)
        }

        // Service info
        String serviceName = context.get(CONSUL_SERVICE_NAME) as String
        if (serviceName) {
            doc.put('service', serviceName)
            // Extract team prefix
            if (serviceName.contains('-')) {
                doc.put('team', serviceName.split('-')[0])
            }
        }

        // Request info
        def inboundRequest = response.getInboundRequest()
        if (inboundRequest) {
            doc.put('method', inboundRequest.getMethod() ?: 'unknown')
            doc.put('original_uri', context.get(CONSUL_ORIGINAL_URI) as String ?: inboundRequest.getPath())
        }
        doc.put('path', context.get(CONSUL_REQUEST_PATH) as String ?: '/')

        String serviceUri = context.get(CONSUL_SERVICE_URI) as String
        if (serviceUri) {
            doc.put('backend_uri', serviceUri)
        }

        // Response info
        doc.put('status', response.getStatus())

        // Duration
        Long startNano = context.get(CONSUL_START_NANO) as Long
        if (startNano) {
            long durationMs = Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L)
            doc.put('duration_ms', durationMs)
        }

        // Error info if applicable
        if (response.getStatus() >= 400) {
            doc.put('error', true)
            if (response.getStatus() >= 500) {
                doc.put('server_error', true)
            }
        }

        return doc
    }
}
