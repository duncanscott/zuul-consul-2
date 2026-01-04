package inbound

import com.netflix.zuul.filters.http.HttpInboundSyncFilter
import com.netflix.zuul.message.http.HttpRequestMessage
import groovy.util.logging.Slf4j
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import org.slf4j.MDC

import java.util.concurrent.ThreadLocalRandom

/**
 * Early inbound filter that assigns a W3C Trace Context compatible request ID.
 * <p>
 * This filter implements a hybrid approach:
 * <ul>
 *   <li>If OpenTelemetry agent is active, uses the trace ID from the current span</li>
 *   <li>Otherwise, generates a W3C-compatible 32-hex-char trace ID</li>
 * </ul>
 * <p>
 * The trace ID is:
 * <ul>
 *   <li>Stored in MDC as 'trace.id' for inclusion in all log messages</li>
 *   <li>Stored in the session context for use by other filters</li>
 *   <li>Used by ConsulRoutingFilter for W3C traceparent headers</li>
 * </ul>
 * <p>
 * W3C Trace Context format:
 * <ul>
 *   <li>trace-id: 32 lowercase hex characters (16 bytes)</li>
 *   <li>span-id: 16 lowercase hex characters (8 bytes)</li>
 *   <li>traceparent: {version}-{trace-id}-{span-id}-{flags}</li>
 * </ul>
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 */
@Slf4j
class RequestIdFilter extends HttpInboundSyncFilter {

    // Context keys
    static final String TRACE_ID = 'trace.id'
    static final String SPAN_ID = 'span.id'
    static final String TRACE_FLAGS = 'trace.flags'
    static final String OTEL_ACTIVE = 'otel.active'

    // MDC keys
    static final String MDC_TRACE_ID = 'trace.id'
    static final String MDC_SPAN_ID = 'span.id'

    // W3C Trace Context constants
    static final String TRACEPARENT_HEADER = 'traceparent'
    static final String TRACESTATE_HEADER = 'tracestate'
    static final String TRACE_VERSION = '00'
    static final String DEFAULT_TRACE_FLAGS = '01'  // sampled

    // Invalid trace ID (all zeros)
    private static final String INVALID_TRACE_ID = '0' * 32
    private static final String INVALID_SPAN_ID = '0' * 16

    @Override
    int filterOrder() {
        return 10  // Run very early
    }

    @Override
    boolean shouldFilter(HttpRequestMessage request) {
        return true
    }

    @Override
    HttpRequestMessage apply(HttpRequestMessage request) {
        String traceId
        String spanId
        String traceFlags = DEFAULT_TRACE_FLAGS
        boolean otelActive = false

        // First, check for incoming traceparent header (propagated context)
        String incomingTraceparent = request.getHeaders().getFirst(TRACEPARENT_HEADER)
        if (incomingTraceparent) {
            def parsed = parseTraceparent(incomingTraceparent)
            if (parsed) {
                traceId = parsed.traceId
                traceFlags = parsed.traceFlags
                // Generate new span ID for this service (we're a new span in the trace)
                spanId = generateSpanId()
                log.debug("Using propagated trace context: traceId={}", traceId)
            }
        }

        // If no valid propagated context, check OpenTelemetry
        if (!traceId) {
            try {
                Span currentSpan = Span.current()
                SpanContext spanContext = currentSpan?.getSpanContext()

                if (spanContext != null && spanContext.isValid()) {
                    traceId = spanContext.getTraceId()
                    spanId = spanContext.getSpanId()
                    traceFlags = spanContext.isSampled() ? '01' : '00'
                    otelActive = true
                    log.debug("Using OpenTelemetry trace context: traceId={}, spanId={}", traceId, spanId)
                }
            } catch (Exception e) {
                // OpenTelemetry not available or error - fall through to generate our own
                log.trace("OpenTelemetry not active: {}", e.message)
            }
        }

        // If still no trace ID, generate W3C-compatible IDs
        if (!traceId || traceId == INVALID_TRACE_ID) {
            traceId = generateTraceId()
            spanId = generateSpanId()
            log.debug("Generated W3C trace context: traceId={}, spanId={}", traceId, spanId)
        }

        // Store in session context for other filters
        def context = request.getContext()
        context.set(TRACE_ID, traceId)
        context.set(SPAN_ID, spanId)
        context.set(TRACE_FLAGS, traceFlags)
        context.set(OTEL_ACTIVE, otelActive)

        // Add to MDC for logging (trace.id correlates with APM)
        MDC.put(MDC_TRACE_ID, traceId)
        MDC.put(MDC_SPAN_ID, spanId)

        return request
    }

    /**
     * Generate a W3C-compatible trace ID (32 lowercase hex characters).
     * Uses ThreadLocalRandom for good performance without synchronization.
     */
    static String generateTraceId() {
        def random = ThreadLocalRandom.current()
        return String.format('%016x%016x', random.nextLong(), random.nextLong())
    }

    /**
     * Generate a W3C-compatible span ID (16 lowercase hex characters).
     */
    static String generateSpanId() {
        return String.format('%016x', ThreadLocalRandom.current().nextLong())
    }

    /**
     * Build a W3C traceparent header value.
     * Format: {version}-{trace-id}-{span-id}-{trace-flags}
     * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-b7ad6b7169203331-01
     */
    static String buildTraceparent(String traceId, String spanId, String traceFlags) {
        return "${TRACE_VERSION}-${traceId}-${spanId}-${traceFlags}"
    }

    /**
     * Parse a traceparent header value.
     * Returns null if invalid.
     */
    private static Map parseTraceparent(String traceparent) {
        if (!traceparent) return null

        def parts = traceparent.split('-')
        if (parts.length != 4) return null

        String version = parts[0]
        String traceId = parts[1]
        String spanId = parts[2]
        String traceFlags = parts[3]

        // Validate
        if (version != TRACE_VERSION) return null
        if (traceId.length() != 32 || traceId == INVALID_TRACE_ID) return null
        if (spanId.length() != 16 || spanId == INVALID_SPAN_ID) return null
        if (traceFlags.length() != 2) return null

        return [traceId: traceId, spanId: spanId, traceFlags: traceFlags]
    }
}
