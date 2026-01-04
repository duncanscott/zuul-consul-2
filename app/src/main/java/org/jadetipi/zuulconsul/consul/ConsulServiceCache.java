package org.jadetipi.zuulconsul.consul;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thread-safe cache of Consul services organized by service name and tags.
 * <p>
 * This cache uses two layers:
 * <ul>
 *   <li>Primary storage: ConcurrentHashMap of service name -> service instances</li>
 *   <li>Lookup cache: Caffeine cache for tag-filtered lookup results</li>
 * </ul>
 * <p>
 * The lookup cache (similar to the old OnDemandCacheMapped pattern) ensures that
 * repeated lookups with the same service name and tags are O(1) after the first lookup.
 * When services are updated via Consul watches, the lookup cache entries for that
 * service are automatically invalidated.
 */
public class ConsulServiceCache {

    private static final Logger log = LoggerFactory.getLogger(ConsulServiceCache.class);

    // Default cache configuration
    private static final int DEFAULT_LOOKUP_CACHE_SIZE = 10_000;
    private static final long DEFAULT_LOOKUP_CACHE_EXPIRE_MINUTES = 60;

    // Map of service name -> list of ConsulService instances
    private final Map<String, List<ConsulService>> servicesByName = new ConcurrentHashMap<>();

    // Set of reachable environments (e.g., "dev", "int", "prd")
    private final Set<String> reachableEnvironments;

    // Caffeine cache for tag-filtered lookup results
    // Key: ServiceLookupKey (serviceName + sorted tags), Value: filtered service list
    private final Cache<ServiceLookupKey, List<ConsulService>> lookupCache;

    // Track which lookup keys are associated with each service for invalidation
    private final Map<String, Set<ServiceLookupKey>> lookupKeysByService = new ConcurrentHashMap<>();

    public ConsulServiceCache(Set<String> reachableEnvironments) {
        this(reachableEnvironments, DEFAULT_LOOKUP_CACHE_SIZE, DEFAULT_LOOKUP_CACHE_EXPIRE_MINUTES);
    }

    public ConsulServiceCache(Set<String> reachableEnvironments, int maxLookupCacheSize, long expireMinutes) {
        this.reachableEnvironments = reachableEnvironments != null
            ? Collections.unmodifiableSet(new HashSet<>(reachableEnvironments))
            : Collections.emptySet();

        this.lookupCache = Caffeine.newBuilder()
            .maximumSize(maxLookupCacheSize)
            .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
            .recordStats()
            .build();

        log.info("ConsulServiceCache initialized with lookup cache: maxSize={}, expireMinutes={}",
            maxLookupCacheSize, expireMinutes);
    }

    /**
     * Update the cache with services for a given service name.
     * Invalidates any cached lookup results for this service.
     */
    public void updateServices(String serviceName, List<ConsulService> services) {
        if (serviceName == null || services == null) {
            return;
        }
        servicesByName.put(serviceName, new ArrayList<>(services));

        // Invalidate lookup cache entries for this service
        invalidateLookupCacheForService(serviceName);

        log.debug("Updated cache for service '{}' with {} instances", serviceName, services.size());
    }

    /**
     * Invalidate all lookup cache entries for a specific service.
     */
    private void invalidateLookupCacheForService(String serviceName) {
        Set<ServiceLookupKey> keysToInvalidate = lookupKeysByService.remove(serviceName);
        if (keysToInvalidate != null && !keysToInvalidate.isEmpty()) {
            lookupCache.invalidateAll(keysToInvalidate);
            log.debug("Invalidated {} lookup cache entries for service '{}'",
                keysToInvalidate.size(), serviceName);
        }
    }

    /**
     * Get all services with the given name.
     */
    public List<ConsulService> getServices(String serviceName) {
        return servicesByName.getOrDefault(serviceName, Collections.emptyList());
    }

    /**
     * Get services matching the given name and tags.
     * Results are cached for subsequent lookups with the same parameters.
     * <p>
     * IMPORTANT: Empty results are NOT cached to prevent cache pollution from
     * requests for non-existent services or invalid tag combinations.
     *
     * @param serviceName the service name
     * @param tags        the tags to filter by (e.g., ["env:dev", "version:default"])
     * @return list of matching services (may be empty, never null)
     */
    public List<ConsulService> getServices(String serviceName, List<String> tags) {
        if (serviceName == null) {
            return Collections.emptyList();
        }

        // Create lookup key
        ServiceLookupKey lookupKey = new ServiceLookupKey(serviceName, tags);

        // Try to get from lookup cache
        List<ConsulService> cached = lookupCache.getIfPresent(lookupKey);
        if (cached != null) {
            log.trace("Lookup cache hit for {}", lookupKey);
            return cached;
        }

        // Cache miss - compute the result
        List<ConsulService> services = getServices(serviceName);
        List<ConsulService> result;

        if (tags == null || tags.isEmpty()) {
            result = services;
        } else {
            result = services.stream()
                .filter(service -> service.matchesTags(tags))
                .collect(Collectors.toList());
        }

        // Only cache non-empty results to prevent cache pollution from
        // requests for non-existent services (similar to old OnDemandCacheMapped with cacheNulls=false)
        if (!result.isEmpty()) {
            lookupCache.put(lookupKey, result);

            // Track the lookup key for this service (for invalidation)
            lookupKeysByService
                .computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet())
                .add(lookupKey);

            log.trace("Lookup cache miss for {}, cached {} results", lookupKey, result.size());
        } else {
            log.trace("Lookup cache miss for {}, empty result not cached", lookupKey);
        }

        return result;
    }

    /**
     * Get a single service matching the given name and tags.
     * Prefers healthy instances, falls back to unhealthy if none are healthy.
     * Within each health category, returns the first match (sorted by proximity to local agent).
     */
    public Optional<ConsulService> getService(String serviceName, List<String> tags) {
        List<ConsulService> matches = getServices(serviceName, tags);
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        // Prefer healthy instances
        Optional<ConsulService> healthy = matches.stream()
            .filter(ConsulService::isHealthy)
            .findFirst();

        if (healthy.isPresent()) {
            return healthy;
        }

        // Fall back to first (nearest) unhealthy instance
        log.warn("No healthy instances for service '{}', falling back to unhealthy", serviceName);
        return Optional.of(matches.get(0));
    }

    /**
     * Check if the given environment is reachable.
     */
    public boolean isEnvironmentReachable(String environment) {
        if (reachableEnvironments.isEmpty()) {
            return true; // No restrictions
        }
        return reachableEnvironments.contains(environment);
    }

    /**
     * Get the set of reachable environments.
     */
    public Set<String> getReachableEnvironments() {
        return reachableEnvironments;
    }

    /**
     * Get all cached service names.
     */
    public Set<String> getServiceNames() {
        return Collections.unmodifiableSet(servicesByName.keySet());
    }

    /**
     * Get the total number of service instances cached.
     */
    public int getTotalInstanceCount() {
        return servicesByName.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Clear all cached services and lookup cache.
     */
    public void clear() {
        servicesByName.clear();
        lookupCache.invalidateAll();
        lookupKeysByService.clear();
        log.info("Service cache cleared (including lookup cache)");
    }

    /**
     * Remove a service from the cache and invalidate related lookup entries.
     */
    public void removeService(String serviceName) {
        servicesByName.remove(serviceName);
        invalidateLookupCacheForService(serviceName);
    }

    /**
     * Get lookup cache statistics.
     */
    public Map<String, Object> getLookupCacheStats() {
        var stats = lookupCache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hitCount", stats.hitCount());
        result.put("missCount", stats.missCount());
        result.put("hitRate", String.format("%.2f%%", stats.hitRate() * 100));
        result.put("evictionCount", stats.evictionCount());
        result.put("size", lookupCache.estimatedSize());
        return result;
    }

    /**
     * Get all accessed lookup keys for cache warming.
     * <p>
     * This returns a snapshot of the service name + tags combinations that have been
     * successfully looked up. Used during cache rotation to warm the new cache with
     * the same lookups that were used in the old cache.
     *
     * @return set of lookup keys as ServiceNameWithTags records
     */
    public Set<ServiceNameWithTags> getAccessedLookupKeys() {
        Set<ServiceNameWithTags> keys = new HashSet<>();
        for (ServiceLookupKey key : lookupCache.asMap().keySet()) {
            keys.add(new ServiceNameWithTags(key.serviceName, key.sortedTags));
        }
        return keys;
    }

    /**
     * Warm the cache by pre-loading specific lookup keys.
     * <p>
     * Called during cache rotation to ensure the new cache has the same
     * lookups pre-computed as the old cache.
     *
     * @param keysToWarm the lookup keys to warm
     */
    public void warmCache(Set<ServiceNameWithTags> keysToWarm) {
        if (keysToWarm == null || keysToWarm.isEmpty()) {
            return;
        }
        log.info("Warming cache with {} lookup keys", keysToWarm.size());
        int warmed = 0;
        for (ServiceNameWithTags key : keysToWarm) {
            List<ConsulService> result = getServices(key.serviceName(), key.tags());
            if (!result.isEmpty()) {
                warmed++;
            }
        }
        log.info("Cache warming complete: {} of {} keys produced results", warmed, keysToWarm.size());
    }

    /**
     * Record representing a service name with its associated tags.
     * Used for cache warming during cache rotation.
     */
    public record ServiceNameWithTags(String serviceName, List<String> tags) {
        public ServiceNameWithTags {
            // Defensive copy and null safety
            tags = tags != null ? List.copyOf(tags) : List.of();
        }
    }

    /**
     * Convert the cache to a JSON-friendly map structure.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reachableEnvironments", reachableEnvironments);
        result.put("serviceCount", servicesByName.size());
        result.put("totalInstances", getTotalInstanceCount());

        Map<String, List<Map<String, Object>>> servicesMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<ConsulService>> entry : servicesByName.entrySet()) {
            List<Map<String, Object>> instances = entry.getValue().stream()
                .map(this::serviceToMap)
                .collect(Collectors.toList());
            servicesMap.put(entry.getKey(), instances);
        }
        result.put("services", servicesMap);

        return result;
    }

    private Map<String, Object> serviceToMap(ConsulService service) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", service.getId());
        map.put("uri", service.getUri().toString());
        map.put("healthy", service.isHealthy());
        map.put("status", service.getHealthStatus().name());
        map.put("tags", service.getTags());
        if (service.getDocsUrl() != null) {
            map.put("docs", service.getDocsUrl());
        }
        return map;
    }

    /**
     * Cache key for service lookups.
     * Combines service name with sorted tags for consistent hashing.
     */
    private static final class ServiceLookupKey {
        private final String serviceName;
        private final List<String> sortedTags;
        private final int hashCode;

        ServiceLookupKey(String serviceName, List<String> tags) {
            this.serviceName = serviceName;
            // Sort tags for consistent key matching regardless of input order
            // Filter out null values to prevent NPE during sort
            if (tags == null || tags.isEmpty()) {
                this.sortedTags = Collections.emptyList();
            } else {
                List<String> sorted = tags.stream()
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());
                this.sortedTags = Collections.unmodifiableList(sorted);
            }
            // Pre-compute hash code for performance
            this.hashCode = Objects.hash(this.serviceName, this.sortedTags);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ServiceLookupKey that = (ServiceLookupKey) o;
            return Objects.equals(serviceName, that.serviceName) &&
                   Objects.equals(sortedTags, that.sortedTags);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return serviceName + (sortedTags.isEmpty() ? "" : sortedTags.toString());
        }
    }
}
