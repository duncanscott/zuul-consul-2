package inbound

import com.netflix.zuul.filters.http.HttpInboundSyncFilter
import com.netflix.zuul.message.http.HttpRequestMessage
import groovy.util.logging.Slf4j

/**
 * Inbound filter that buffers JSON request bodies for logging purposes.
 * <p>
 * This filter runs early in the chain and buffers the request body if the
 * Content-Type is application/json. The body is stored in the session context
 * for later use by the CouchDbStatsFilter.
 * <p>
 * Body buffering is disabled by default. Enable it by setting:
 * <pre>
 * ZUUL_BUFFER_REQUEST_BODY=true
 * </pre>
 * <p>
 * Note: Buffering request bodies has memory implications for large payloads.
 * Consider setting a maximum body size limit in production.
 */
@Slf4j
class BodyBufferFilter extends HttpInboundSyncFilter {

    // Environment variable to enable/disable body buffering
    static final String ENV_BUFFER_ENABLED = 'ZUUL_BUFFER_REQUEST_BODY'

    // Context key for storing buffered request body
    static final String REQUEST_BODY_KEY = 'consul.requestBody'
    static final String REQUEST_BODY_CONTENT_TYPE = 'consul.requestBodyContentType'

    // Maximum body size to buffer (default 1MB)
    static final int MAX_BODY_SIZE = 1024 * 1024

    private final boolean enabled

    BodyBufferFilter() {
        this.enabled = 'true'.equalsIgnoreCase(System.getenv(ENV_BUFFER_ENABLED))
        if (enabled) {
            log.info("Request body buffering enabled")
        } else {
            log.debug("Request body buffering disabled (set {}=true to enable)", ENV_BUFFER_ENABLED)
        }
    }

    @Override
    int filterOrder() {
        return 5  // Run very early, before RequestIdFilter (10)
    }

    @Override
    boolean shouldFilter(HttpRequestMessage request) {
        if (!enabled) {
            return false
        }
        // Only buffer if Content-Type indicates JSON
        String contentType = request.getHeaders()?.getFirst('Content-Type')
        return contentType != null && contentType.toLowerCase().contains('application/json')
    }

    @Override
    boolean needsBodyBuffered(HttpRequestMessage request) {
        // This tells Zuul to buffer the entire body before calling apply()
        return shouldFilter(request)
    }

    @Override
    HttpRequestMessage apply(HttpRequestMessage request) {
        try {
            String contentType = request.getHeaders()?.getFirst('Content-Type')

            // Check if body exists and is complete
            if (request.hasCompleteBody()) {
                byte[] bodyBytes = request.getBody()
                if (bodyBytes != null && bodyBytes.length > 0 && bodyBytes.length <= MAX_BODY_SIZE) {
                    String bodyText = new String(bodyBytes, 'UTF-8')
                    request.getContext().set(REQUEST_BODY_KEY, bodyText)
                    request.getContext().set(REQUEST_BODY_CONTENT_TYPE, contentType)
                    log.trace("Buffered request body: {} bytes", bodyBytes.length)
                } else if (bodyBytes != null && bodyBytes.length > MAX_BODY_SIZE) {
                    log.debug("Request body too large to buffer: {} bytes", bodyBytes.length)
                }
            }
        } catch (Exception e) {
            log.warn("Failed to buffer request body: {}", e.message)
        }
        return request
    }
}
