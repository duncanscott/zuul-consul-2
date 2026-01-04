package org.jadetipi.zuulconsul.origins;

import com.netflix.spectator.api.Registry;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.origins.OriginManager;
import com.netflix.zuul.origins.OriginName;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Origin manager that creates Consul-based origins.
 * <p>
 * This manager integrates with Zuul 2's origin infrastructure while using
 * Consul for service discovery. It caches origins by service name and tags
 * to reuse connection pools.
 * <p>
 * Tags are extracted from the session context (set by ConsulRoutingFilter)
 * to support tag-based routing.
 */
public class ConsulOriginManager implements OriginManager<ConsulNettyOrigin> {

    private static final Logger log = LoggerFactory.getLogger(ConsulOriginManager.class);

    public static final String CONSUL_SERVICE_TAGS = "consul.serviceTags";

    private final Registry registry;
    private final ConsulServiceRegistry consulRegistry;
    private final ConcurrentHashMap<String, ConsulNettyOrigin> originMappings;

    /**
     * Create a Consul origin manager.
     *
     * @param registry       Spectator metrics registry
     * @param consulRegistry Consul service registry
     */
    public ConsulOriginManager(Registry registry, ConsulServiceRegistry consulRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.consulRegistry = Objects.requireNonNull(consulRegistry, "consulRegistry must not be null");
        this.originMappings = new ConcurrentHashMap<>();

        log.info("ConsulOriginManager initialized");
    }

    @Override
    public ConsulNettyOrigin getOrigin(OriginName originName, String uri, SessionContext ctx) {
        List<String> tags = extractTags(ctx);
        String cacheKey = buildCacheKey(originName, tags);

        return originMappings.computeIfAbsent(cacheKey, k -> createOrigin(originName, uri, ctx));
    }

    @Override
    public ConsulNettyOrigin createOrigin(OriginName originName, String uri, SessionContext ctx) {
        List<String> tags = extractTags(ctx);

        log.debug("Creating new ConsulNettyOrigin for service: {}, tags: {}", originName.getTarget(), tags);

        return new ConsulNettyOrigin(originName, registry, consulRegistry, tags);
    }

    /**
     * Extract tags from the session context.
     * These are set by ConsulRoutingFilter during request processing.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractTags(SessionContext ctx) {
        if (ctx == null) {
            return Collections.emptyList();
        }

        Object tagsObj = ctx.get(CONSUL_SERVICE_TAGS);
        if (tagsObj instanceof List) {
            return (List<String>) tagsObj;
        }

        return Collections.emptyList();
    }

    /**
     * Build a cache key from origin name and tags.
     * This allows different origins for the same service with different tags.
     */
    private String buildCacheKey(OriginName originName, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return originName.getNiwsClientName();
        }

        // Sort tags for consistent cache keys
        List<String> sortedTags = new ArrayList<>(tags);
        Collections.sort(sortedTags);

        StringBuilder sb = new StringBuilder(originName.getNiwsClientName());
        for (String tag : sortedTags) {
            sb.append("::").append(tag);
        }
        return sb.toString();
    }

    /**
     * Get the number of cached origins.
     */
    public int getCachedOriginCount() {
        return originMappings.size();
    }

    /**
     * Get all cached origin names.
     */
    public Set<String> getCachedOriginNames() {
        return Collections.unmodifiableSet(originMappings.keySet());
    }

    /**
     * Shutdown all origins and release resources.
     */
    public void shutdown() {
        log.info("Shutting down ConsulOriginManager with {} cached origins", originMappings.size());
        for (ConsulNettyOrigin origin : originMappings.values()) {
            try {
                origin.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down origin: {}", origin.getName(), e);
            }
        }
        originMappings.clear();
    }
}
