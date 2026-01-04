package org.jadetipi.zuulconsul.filters;

import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.filters.http.HttpInboundSyncFilter;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpQueryParams;
import org.jadetipi.zuulconsul.consul.ConsulService;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds a service's Consul context root to the outbound request path and
 * records the resolved backend URI for logging.
 */
public class ContextRootFilter extends HttpInboundSyncFilter {

    private static final String CONSUL_SERVICE_NAME = "consul.serviceName";
    private static final String CONSUL_SERVICE_TAGS = "consul.serviceTags";
    private static final String CONSUL_REQUEST_PATH = "consul.requestPath";
    private static final String CONSUL_SERVICE_URI = "consul.serviceUri";

    private final ConsulServiceRegistry registry;

    public ContextRootFilter(ConsulServiceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public int filterOrder() {
        // Run after ConsulRoutingFilter (100) but before proxying begins
        return 150;
    }

    @Override
    public boolean shouldFilter(HttpRequestMessage request) {
        SessionContext ctx = request.getContext();
        return ctx != null && ctx.get(CONSUL_SERVICE_NAME) != null;
    }

    @Override
    public HttpRequestMessage apply(HttpRequestMessage request) {
        SessionContext ctx = request.getContext();
        String serviceName = (String) ctx.get(CONSUL_SERVICE_NAME);
        if (serviceName == null) {
            return request;
        }

        List<String> tags = extractTags(ctx.get(CONSUL_SERVICE_TAGS));
        Optional<ConsulService> serviceOpt = registry.getService(serviceName, tags);
        if (serviceOpt.isEmpty()) {
            return request;
        }

        ConsulService service = serviceOpt.get();
        String currentPath = normalizePath(request.getPath());
        String contextRoot = normalizePath(service.getContextRoot());

        String combinedPath = combinePaths(contextRoot, currentPath);
        request.setPath(combinedPath);

        String query = resolveQueryString(request);
        ctx.set(CONSUL_REQUEST_PATH, buildPathWithQuery(combinedPath, query));
        ctx.set(CONSUL_SERVICE_URI, buildFullUriString(service, combinedPath, query));

        return request;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTags(Object tagsObj) {
        if (tagsObj instanceof List) {
            return (List<String>) tagsObj;
        }
        return Collections.emptyList();
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.equals("/")) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String combinePaths(String contextRoot, String requestPath) {
        if ("/".equals(contextRoot)) {
            return requestPath;
        }
        if (requestPath.startsWith(contextRoot)) {
            return requestPath;
        }
        if ("/".equals(requestPath)) {
            return contextRoot;
        }
        String base = contextRoot.endsWith("/") ? contextRoot.substring(0, contextRoot.length() - 1) : contextRoot;
        return base + requestPath;
    }

    private String buildPathWithQuery(String path, String query) {
        if (query != null && !query.isEmpty()) {
            return path + "?" + query;
        }
        return path;
    }

    private String buildFullUriString(ConsulService service, String path, String query) {
        try {
            URI base = service.getUri();
            URI resolved = new URI(
                base.getScheme(),
                base.getUserInfo(),
                base.getHost(),
                base.getPort(),
                path,
                query,
                null);
            return resolved.toString();
        } catch (Exception e) {
            return service.getUri().toString();
        }
    }

    private String resolveQueryString(HttpRequestMessage request) {
        Object params = request.getQueryParams();
        if (params instanceof HttpQueryParams) {
            return ((HttpQueryParams) params).toEncodedString();
        }
        return null;
    }
}
