package outbound

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.Spectator
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.filters.http.HttpOutboundSyncFilter
import com.netflix.zuul.message.http.HttpResponseMessage
import groovy.util.logging.Slf4j
import inbound.RequestIdFilter
import org.slf4j.MDC

import java.util.concurrent.TimeUnit

/**
 * Outbound filter that logs request statistics.
 * <p>
 * This filter runs last in the outbound chain to capture final request state,
 * including timing, status codes, and routing information.
 * <p>
 * Uses SLF4J MDC for structured logging compatible with ELK stack.
 * Field names match the original zuul-consul Stats.groovy for log parsing compatibility.
 * <p>
 * Note: For future fields, consider Elastic Common Schema (ECS) naming conventions.
 * Current field names are kept for backward compatibility with existing log parsing.
 *
 * @see <a href="https://www.elastic.co/guide/en/ecs/current/ecs-field-reference.html">ECS Field Reference</a>
 */
@Slf4j
class StatsFilter extends HttpOutboundSyncFilter {

    // Context keys (set by ConsulRoutingFilter)
    static final String CONSUL_SERVICE_NAME = 'consul.serviceName'
    static final String CONSUL_SERVICE_TAGS = 'consul.serviceTags'
    static final String CONSUL_SERVICE_URI = 'consul.serviceUri'
    static final String CONSUL_REQUEST_PATH = 'consul.requestPath'
    static final String CONSUL_ORIGINAL_URI = 'consul.originalUri'
    static final String CONSUL_START_NANO = 'consul.startNano'

    // List of MDC keys we set (for cleanup)
    private static final List<String> MDC_KEYS = [
        // Trace context (set by RequestIdFilter, cleared here)
        'trace.id',
        'span.id',
        // HTTP fields (ECS/OTel-compatible)
        'http.response.status_code',
        'http.request.method',
        // URL fields (ECS/OTel-compatible)
        'url.path',
        // Service fields (legacy naming)
        'service.name',
        'service.host.name',
        'service.port',
        'service.url.path',
        // OpenTelemetry semantic convention fields
        // @see https://opentelemetry.io/docs/specs/semconv/http/http-spans/
        'server.address',      // OTel equivalent of service.host.name
        'server.port',         // OTel equivalent of service.port
        'client.address',      // OTel equivalent of forwarded_for_ip
        // Custom fields
        'fields.team',
        'milliseconds',
        'forwarded_for_ip',
        'forwarded_for_host'
    ]

    private final Registry registry

    StatsFilter() {
        this(Spectator.globalRegistry())
    }

    StatsFilter(Registry registry) {
        this.registry = registry
    }

    @Override
    int filterOrder() {
        return 2000  // Run last
    }

    @Override
    boolean shouldFilter(HttpResponseMessage response) {
        return true
    }

    @Override
    HttpResponseMessage apply(HttpResponseMessage response) {
        SessionContext context = response.getContext()

        // Skip logging if no service was routed (e.g., root path returns registry)
        String serviceName = context.get(CONSUL_SERVICE_NAME) as String
        String serviceUri = context.get(CONSUL_SERVICE_URI) as String
        if (!serviceUri) {
            log.debug("No service URI - skipping stats logging")
            clearMdc()
            return response
        }

        recordMetrics(context, response)
        try {
            setMdcValues(context, response, serviceName, serviceUri)
            logRequest(context, response, serviceUri, serviceName)
        } finally {
            clearMdc()
        }

        return response
    }

    private void setMdcValues(SessionContext context, HttpResponseMessage response,
                              String serviceName, String serviceUri) {
        // Trace IDs are already set by RequestIdFilter, but ensure they're present
        // (they're added to MDC keys list for cleanup)

        // HTTP fields (ECS-compatible: https://www.elastic.co/guide/en/ecs/current/ecs-http.html)
        MDC.put('http.response.status_code', String.valueOf(response.getStatus()))
        MDC.put('http.request.method', response.getInboundRequest()?.getMethod() ?: 'unknown')

        // URL path (ECS-compatible: https://www.elastic.co/guide/en/ecs/current/ecs-url.html)
        MDC.put('url.path', context.get(CONSUL_REQUEST_PATH) as String ?: '/')

        // Service fields
        MDC.put('service.name', serviceName ?: 'unknown')

        // Parse service URI for host, port, path
        if (serviceUri) {
            try {
                URI uri = new URI(serviceUri)
                if (uri.host) {
                    MDC.put('service.host.name', uri.host)
                    MDC.put('server.address', uri.host)  // OTel semantic convention
                }
                if (uri.port > 0) {
                    String portStr = String.valueOf(uri.port)
                    MDC.put('service.port', portStr)
                    MDC.put('server.port', portStr)  // OTel semantic convention
                }
                if (uri.path) {
                    MDC.put('service.url.path', uri.path)
                }
            } catch (Exception e) {
                log.trace("Failed to parse service URI: {}", e.message)
            }
        }

        // Team extraction (e.g., "pps-my-service" -> "pps")
        if (serviceName?.contains('-')) {
            MDC.put('fields.team', serviceName.split('-')[0])
        }

        // Request duration
        Long startNano = context.get(CONSUL_START_NANO) as Long
        if (startNano) {
            long elapsed = Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L)
            MDC.put('milliseconds', String.valueOf(elapsed))
        }

        // Dynamic tags from URI (e.g., env:dev -> tag.env=dev)
        List<String> tags = context.get(CONSUL_SERVICE_TAGS) as List<String>
        tags?.each { String tag ->
            if (tag?.contains(':')) {
                String[] parts = tag.split(':', 2)
                if (parts.length == 2) {
                    MDC.put("tag.${parts[0]}", parts[1])
                }
            }
        }

        // X-Forwarded-For handling
        String forwardedFor = response.getInboundRequest()?.getHeaders()?.getFirst('X-Forwarded-For')
        if (forwardedFor) {
            // Take first IP if multiple (client IP)
            String clientIp = forwardedFor.split(',')[0].trim()
            MDC.put('forwarded_for_ip', clientIp)
            MDC.put('client.address', clientIp)  // OTel semantic convention
            // Note: DNS lookup for forwarded_for_host is intentionally skipped
            // as it can be slow and block the event loop. If needed, consider
            // async lookup in a separate component.
        }
    }

    private void logRequest(SessionContext context, HttpResponseMessage response,
                           String serviceUri, String serviceName) {
        def request = response.getInboundRequest()
        String originalUri = context.get(CONSUL_ORIGINAL_URI) as String ?: request?.getPath()

        Long startNano = context.get(CONSUL_START_NANO) as Long
        Long elapsed = startNano ? Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L) : null

        List<String> messageComponents = []
        messageComponents.add("request URL: ${originalUri}")
        messageComponents.add("service URL: ${serviceUri}")
        messageComponents.add("service name: ${serviceName}")
        if (elapsed != null) {
            messageComponents.add("milliseconds: ${elapsed}")
        }
        messageComponents.add("response_code: ${response.getStatus()}")

        log.info(messageComponents.join('; '))
    }

    private void clearMdc() {
        MDC_KEYS.each { MDC.remove(it) }
        // Also clear dynamic tag.* keys
        MDC.getCopyOfContextMap()?.keySet()?.findAll { it.startsWith('tag.') }?.each {
            MDC.remove(it)
        }
    }

    private void recordMetrics(SessionContext context, HttpResponseMessage response) {
        if (registry == null) {
            return
        }
        try {
            Long start = context.get(CONSUL_START_NANO) as Long
            if (start == null) {
                return
            }
            long durationMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L)
            def serviceName = context.get(CONSUL_SERVICE_NAME) as String ?: 'unknown'
            def method = response.getInboundRequest()?.getMethod() ?: 'unknown'
            registry.timer(
                'zuul.request.duration',
                'service', serviceName,
                'status', String.valueOf(response.getStatus()),
                'method', method
            ).record(durationMs, TimeUnit.MILLISECONDS)
        } catch (Exception e) {
            log.warn("Failed to record metrics", e)
        }
    }
}
