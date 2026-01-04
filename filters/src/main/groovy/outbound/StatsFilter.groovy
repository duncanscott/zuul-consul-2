package outbound

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.Spectator
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.filters.http.HttpOutboundSyncFilter
import com.netflix.zuul.message.http.HttpResponseMessage
import groovy.util.logging.Slf4j
import org.slf4j.MDC

import java.util.concurrent.TimeUnit

/**
 * Outbound filter that logs request statistics.
 * <p>
 * This filter runs last in the outbound chain to capture final request state,
 * including timing, status codes, and routing information.
 * <p>
 * Uses SLF4J MDC for structured logging compatible with ELK stack.
 */
@Slf4j
class StatsFilter extends HttpOutboundSyncFilter {

    static final String CONSUL_SERVICE_NAME = 'consul.serviceName'
    static final String CONSUL_SERVICE_URI = 'consul.serviceUri'
    static final String CONSUL_REQUEST_PATH = 'consul.requestPath'
    static final String CONSUL_START_NANO = 'consul.startNano'

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

        recordMetrics(context, response)
        try {
            // Set MDC values for structured logging
            MDC.put('service.name', context.get(CONSUL_SERVICE_NAME) as String ?: 'unknown')
            MDC.put('http.request.method', response.getInboundRequest()?.getMethod() ?: 'unknown')
            MDC.put('http.response.status_code', String.valueOf(response.getStatus()))
            MDC.put('url.path', context.get(CONSUL_REQUEST_PATH) as String ?: '/')

            String serviceUri = context.get(CONSUL_SERVICE_URI) as String
            if (serviceUri) {
                MDC.put('url.full', serviceUri)
            }

            // Extract team from service name prefix (e.g., "pps-" -> "pps")
            String serviceName = context.get(CONSUL_SERVICE_NAME) as String
            if (serviceName?.contains('-')) {
                MDC.put('service.team', serviceName.split('-')[0])
            }

            // Log the request
            log.info("Request completed: {} {} -> {}",
                response.getInboundRequest()?.getMethod(),
                response.getInboundRequest()?.getPath(),
                response.getStatus())

        } finally {
            // Clear MDC
            MDC.remove('service.name')
            MDC.remove('http.request.method')
            MDC.remove('http.response.status_code')
            MDC.remove('url.path')
            MDC.remove('url.full')
            MDC.remove('service.team')
        }

        return response
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
