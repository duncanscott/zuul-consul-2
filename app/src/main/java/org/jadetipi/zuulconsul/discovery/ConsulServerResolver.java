package org.jadetipi.zuulconsul.discovery;

import com.netflix.zuul.discovery.DiscoveryResult;
import com.netflix.zuul.resolver.Resolver;
import com.netflix.zuul.resolver.ResolverListener;
import org.jadetipi.zuulconsul.consul.ConsulService;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Resolver implementation that looks up services from Consul.
 * <p>
 * This resolver uses the ConsulServiceRegistry to find healthy service
 * instances and returns them as DiscoveryResult objects compatible with
 * Zuul's connection pool infrastructure.
 * <p>
 * Uses coordinate-based RTT estimation for intelligent routing:
 * <ul>
 *   <li>Groups instances within an RTT threshold as "equidistant"</li>
 *   <li>Round-robin within the equidistant group</li>
 *   <li>Prefers healthy instances, falls back to unhealthy if needed</li>
 * </ul>
 */
public class ConsulServerResolver implements Resolver<DiscoveryResult>, ConsulServiceRegistry.ServiceChangeListener {

    private static final Logger log = LoggerFactory.getLogger(ConsulServerResolver.class);

    /**
     * Default RTT threshold in milliseconds.
     * Instances within this threshold of the minimum RTT are considered "equidistant"
     * and will receive equal round-robin distribution.
     */
    private static final float DEFAULT_RTT_THRESHOLD_MS = 5.0f;

    private final ConsulServiceRegistry registry;
    private final String serviceName;
    private final List<String> tags;
    private final float rttThresholdMs;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final Map<String, DiscoveryResult> knownResults = new HashMap<>();
    private volatile ResolverListener<DiscoveryResult> listener;
    private volatile boolean shutdown = false;

    /**
     * Create a resolver for a specific service with default RTT threshold.
     *
     * @param registry    the Consul service registry
     * @param serviceName the name of the service to resolve
     * @param tags        tags to filter by (can be null or empty)
     */
    public ConsulServerResolver(ConsulServiceRegistry registry, String serviceName, List<String> tags) {
        this(registry, serviceName, tags, DEFAULT_RTT_THRESHOLD_MS);
    }

    /**
     * Create a resolver for a specific service with custom RTT threshold.
     *
     * @param registry       the Consul service registry
     * @param serviceName    the name of the service to resolve
     * @param tags           tags to filter by (can be null or empty)
     * @param rttThresholdMs RTT threshold in milliseconds for grouping equidistant instances
     */
    public ConsulServerResolver(ConsulServiceRegistry registry, String serviceName, List<String> tags, float rttThresholdMs) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
        this.tags = tags != null ? new ArrayList<>(tags) : Collections.emptyList();
        this.rttThresholdMs = rttThresholdMs;

        List<ConsulService> initial = registry.getServices(serviceName, this.tags);
        refreshKnownResults(initial != null ? initial : Collections.emptyList(), false);
        registry.addServiceChangeListener(serviceName, this);
        log.debug("Created ConsulServerResolver for service: {}, tags: {}, rttThreshold: {}ms",
            serviceName, this.tags, rttThresholdMs);
    }

    @Override
    public DiscoveryResult resolve(Object key) {
        if (shutdown) {
            log.warn("Resolver is shut down, returning EMPTY");
            return DiscoveryResult.EMPTY;
        }

        // Get all instances for this service (healthy and unhealthy)
        List<ConsulService> allServices = registry.getServices(serviceName, tags);

        refreshKnownResults(allServices, false);

        if (allServices.isEmpty()) {
            log.debug("No instances found for service: {}, tags: {}", serviceName, tags);
            return DiscoveryResult.EMPTY;
        }

        // Prefer healthy instances, fall back to unhealthy
        List<ConsulService> healthyServices = allServices.stream()
            .filter(ConsulService::isHealthy)
            .collect(Collectors.toList());

        List<ConsulService> candidateServices;
        if (!healthyServices.isEmpty()) {
            candidateServices = healthyServices;
        } else {
            log.warn("No healthy instances for service '{}', falling back to unhealthy", serviceName);
            candidateServices = allServices;
        }

        // Group instances by RTT threshold (equidistant grouping)
        List<ConsulService> targetServices = selectEquidistantGroup(candidateServices);

        // Round-robin selection within the equidistant group
        int idx = Math.floorMod(roundRobinCounter.getAndIncrement(), targetServices.size());
        ConsulService selected = targetServices.get(idx);

        if (log.isDebugEnabled()) {
            float rtt = registry.getEstimatedRtt(selected);
            log.debug("Resolved service {} to {}:{} (healthy={}, rtt={}ms, instance {} of {} in group)",
                serviceName, selected.getAddress(), selected.getPort(),
                selected.isHealthy(), rtt >= 0 ? String.format("%.2f", rtt) : "unknown",
                idx + 1, targetServices.size());
        }

        return ConsulDiscoveryResult.from(selected);
    }

    /**
     * Select the group of instances that are within the RTT threshold of the nearest instance.
     * If coordinates are not available, returns all instances (pure round-robin).
     *
     * @param services the candidate services to select from
     * @return the equidistant group for round-robin selection
     */
    private List<ConsulService> selectEquidistantGroup(List<ConsulService> services) {
        if (services.size() <= 1) {
            return services;
        }

        // Calculate RTT to each instance
        List<ServiceWithRtt> servicesWithRtt = new ArrayList<>();
        boolean hasCoordinates = false;

        for (ConsulService service : services) {
            float rtt = registry.getEstimatedRtt(service);
            servicesWithRtt.add(new ServiceWithRtt(service, rtt));
            if (rtt >= 0) {
                hasCoordinates = true;
            }
        }

        // If no coordinates available, fall back to pure round-robin
        if (!hasCoordinates) {
            log.debug("No coordinates available for service '{}', using pure round-robin", serviceName);
            return services;
        }

        // Sort by RTT (unknowns go to end)
        servicesWithRtt.sort((a, b) -> {
            if (a.rtt < 0 && b.rtt < 0) return 0;
            if (a.rtt < 0) return 1;
            if (b.rtt < 0) return -1;
            return Float.compare(a.rtt, b.rtt);
        });

        // Find minimum RTT
        float minRtt = -1;
        for (ServiceWithRtt swr : servicesWithRtt) {
            if (swr.rtt >= 0) {
                minRtt = swr.rtt;
                break;
            }
        }

        if (minRtt < 0) {
            // No valid RTT values, use all
            return services;
        }

        // Select instances within threshold of minimum
        List<ConsulService> equidistantGroup = new ArrayList<>();
        for (ServiceWithRtt swr : servicesWithRtt) {
            if (swr.rtt >= 0 && (swr.rtt - minRtt) <= rttThresholdMs) {
                equidistantGroup.add(swr.service);
            } else if (swr.rtt < 0) {
                // Include instances without coordinates in the group
                // (they might be local but just missing coordinate data)
                equidistantGroup.add(swr.service);
            }
        }

        if (log.isDebugEnabled() && equidistantGroup.size() < services.size()) {
            log.debug("Service '{}': selected {} of {} instances within {}ms RTT threshold (minRtt={}ms)",
                serviceName, equidistantGroup.size(), services.size(), rttThresholdMs, minRtt);
        }

        return equidistantGroup.isEmpty() ? services : equidistantGroup;
    }

    /**
     * Helper class to pair a service with its RTT estimate.
     */
    private static class ServiceWithRtt {
        final ConsulService service;
        final float rtt;

        ServiceWithRtt(ConsulService service, float rtt) {
            this.service = service;
            this.rtt = rtt;
        }
    }

    @Override
    public boolean hasServers() {
        if (shutdown) {
            return false;
        }
        return registry.getService(serviceName, tags).isPresent();
    }

    @Override
    public void shutdown() {
        log.debug("Shutting down ConsulServerResolver for service: {}", serviceName);
        this.shutdown = true;
        registry.removeServiceChangeListener(serviceName, this);
        knownResults.clear();
    }

    @Override
    public void setListener(ResolverListener<DiscoveryResult> listener) {
        this.listener = listener;
    }

    @Override
    public void onServiceInstancesChanged(String serviceName) {
        if (!Objects.equals(this.serviceName, serviceName)) {
            return;
        }

        List<ConsulService> currentServices = registry.getServices(serviceName, tags);
        refreshKnownResults(currentServices, true);
    }

    /**
     * Get the service name this resolver is for.
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Get the tags this resolver filters by.
     */
    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    private void refreshKnownResults(List<ConsulService> services, boolean notifyRemoved) {
        List<ConsulService> safeServices = services != null ? services : Collections.emptyList();
        Map<String, DiscoveryResult> latest = new HashMap<>();
        for (ConsulService service : safeServices) {
            latest.put(service.getId(), ConsulDiscoveryResult.from(service));
        }

        List<DiscoveryResult> removed = notifyRemoved ? new ArrayList<>() : null;
        synchronized (knownResults) {
            if (notifyRemoved) {
                for (Map.Entry<String, DiscoveryResult> entry : knownResults.entrySet()) {
                    if (!latest.containsKey(entry.getKey())) {
                        removed.add(entry.getValue());
                    }
                }
            }
            knownResults.clear();
            knownResults.putAll(latest);
        }

        if (removed != null && !removed.isEmpty() && listener != null) {
            log.info("Service {} lost {} instances", serviceName, removed.size());
            listener.onChange(removed);
        }
    }
}
