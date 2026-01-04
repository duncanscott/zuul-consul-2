package org.jadetipi.zuulconsul.consul;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.consul.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Registry for Consul services using the Vert.x Consul client.
 * <p>
 * This is the Zuul 2 equivalent of the original ConsulServiceRegistry.
 * It provides:
 * <ul>
 *   <li>Service discovery via Consul health API</li>
 *   <li>Real-time updates via Consul watches (blocking queries)</li>
 *   <li>Caching of discovered services</li>
 *   <li>Fallback periodic refresh of service catalog</li>
 *   <li>Tag-based service lookup</li>
 * </ul>
 */
public class ConsulServiceRegistry {

    /** Listener for service instance changes. */
    public interface ServiceChangeListener {
        void onServiceInstancesChanged(String serviceName);
    }

    private static final Logger log = LoggerFactory.getLogger(ConsulServiceRegistry.class);

    private static final String AGENT = "_agent";
    private static final long DEFAULT_REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long HEALTH_WATCH_TIMEOUT_SECONDS = 55; // Consul max is 600, but keep it reasonable

    // Rate limiting for Consul health queries to avoid "Too Many Requests" errors
    private static final int HEALTH_BATCH_SIZE = 10;
    private static final long HEALTH_BATCH_DELAY_MS = 100;

    private final Vertx vertx;
    private final ConsulClient consulClient;
    private final ConsulClientOptions consulClientOptions;
    private final AtomicReference<ConsulServiceCache> serviceCacheRef;
    private final Set<String> reachableEnvironments;
    private final String defaultEnvironment;
    private final List<String> defaultTags;
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicLong cacheRotationIndex = new AtomicLong(0);

    // Health state watch for detecting health changes across all services
    private volatile boolean healthStateWatchActive = false;
    private volatile long lastHealthIndex = 0;
    private final Map<String, Set<ServiceChangeListener>> serviceChangeListeners = new ConcurrentHashMap<>();

    // Node coordinates for RTT-based routing
    private final Map<String, Coordinate> nodeCoordinates = new ConcurrentHashMap<>();
    private volatile String localAgentNode;
    private volatile Coordinate localAgentCoordinate;

    private volatile Instant lastLoadTime;
    private volatile long refreshTimerId = -1;

    /**
     * Create a new ConsulServiceRegistry.
     *
     * @param vertx                 the Vert.x instance
     * @param consulHost            Consul agent host (default: localhost)
     * @param consulPort            Consul agent port (default: 8500)
     * @param datacenter            Consul datacenter
     * @param aclToken              ACL token (optional)
     * @param defaultEnvironment    default environment (e.g., "dev")
     * @param reachableEnvironments set of reachable environments
     * @param defaultTags           default tags to apply (e.g., ["version:default"])
     */
    public ConsulServiceRegistry(
            Vertx vertx,
            String consulHost,
            int consulPort,
            String datacenter,
            String aclToken,
            String defaultEnvironment,
            Set<String> reachableEnvironments,
            List<String> defaultTags) {

        this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
        this.defaultEnvironment = defaultEnvironment;
        List<String> tagDefaults = defaultTags != null ? new ArrayList<>(defaultTags) : new ArrayList<>();
        if (defaultEnvironment != null && !defaultEnvironment.isEmpty()) {
            tagDefaults.removeIf(tag -> tag.startsWith("env:"));
            tagDefaults.add("env:" + defaultEnvironment);
        }
        this.defaultTags = tagDefaults;
        Set<String> reachable = reachableEnvironments != null
            ? new HashSet<>(reachableEnvironments)
            : new HashSet<>();
        if (defaultEnvironment != null && !defaultEnvironment.isEmpty()) {
            reachable.add(defaultEnvironment);
        }
        this.reachableEnvironments = Collections.unmodifiableSet(reachable);
        this.serviceCacheRef = new AtomicReference<>(new ConsulServiceCache(reachable));

        this.consulClientOptions = new ConsulClientOptions()
            .setHost(consulHost != null ? consulHost : "localhost")
            .setPort(consulPort > 0 ? consulPort : 8500);

        if (datacenter != null && !datacenter.isEmpty()) {
            consulClientOptions.setDc(datacenter);
        }

        if (aclToken != null && !aclToken.isEmpty()) {
            consulClientOptions.setAclToken(aclToken);
        }

        this.consulClient = ConsulClient.create(vertx, consulClientOptions);

        log.info("ConsulServiceRegistry initialized - host={}, port={}, dc={}, defaultEnv={}",
            consulClientOptions.getHost(), consulClientOptions.getPort(), datacenter, defaultEnvironment);
    }

    /**
     * Create from environment variables.
     */
    public static ConsulServiceRegistry fromEnvironment(Vertx vertx) {
        String host = getEnv("ZUUL_CONSUL_AGENT_HOST", "localhost");
        int port = Integer.parseInt(getEnv("ZUUL_CONSUL_AGENT_PORT", "8500"));
        String datacenter = getEnv("ZUUL_CONSUL_DATACENTER", null);
        String token = getEnv("ZUUL_CONSUL_TOKEN", null);
        String defaultEnv = getEnv("ZUUL_DEFAULT_ENVIRONMENT", "dev");

        Set<String> reachableEnvs = parseReachableEnvironments(
            getEnv("ZUUL_REACHABLE_ENVIRONMENTS", ""));

        List<String> defaultTags = parseDefaultTags(
            getEnv("ZUUL_DEFAULT_TAGS", ""));

        return new ConsulServiceRegistry(
            vertx, host, port, datacenter, token,
            defaultEnv, reachableEnvs, defaultTags);
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    private static Set<String> parseReachableEnvironments(String envString) {
        if (envString == null || envString.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(envString.split(":"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    private static List<String> parseDefaultTags(String tagString) {
        if (tagString == null || tagString.isEmpty()) {
            return Collections.emptyList();
        }
        // Format: "key:value/key2:value2"
        return Arrays.stream(tagString.split("/"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    /**
     * Start watching Consul for changes with real-time updates.
     * Also starts a fallback periodic refresh.
     */
    public void startWatching() {
        startWatching(DEFAULT_REFRESH_INTERVAL_MS);
    }

    /**
     * Start watching Consul for changes with real-time updates.
     * Also starts a fallback periodic refresh with custom interval.
     *
     * @param fallbackRefreshIntervalMs fallback refresh interval in milliseconds
     */
    public void startWatching(long fallbackRefreshIntervalMs) {
        // Initial load
        loadAllServices();

        // Start watching health state (detects health changes across all services)
        // This also detects new/removed services since all services have health checks
        startHealthStateWatch();

        // Start fallback periodic refresh
        startBackgroundRefresh(fallbackRefreshIntervalMs);

        log.info("Started Consul health state watch with fallback refresh interval {}ms", fallbackRefreshIntervalMs);
    }

    /**
     * Start watching health state across all services.
     * Uses blocking queries to detect when any health check changes.
     * This is analogous to the old ChangeListenerHealthServices.
     */
    private void startHealthStateWatch() {
        if (healthStateWatchActive) {
            log.warn("Health state watch already running");
            return;
        }

        healthStateWatchActive = true;
        log.info("Starting health state watch");
        doHealthStateWatch();
    }

    /**
     * Execute a single iteration of the health state watch.
     * Uses a blocking query that returns when any health check changes.
     */
    private void doHealthStateWatch() {
        if (!healthStateWatchActive) {
            return;
        }

        // Create blocking query options with the last known index
        BlockingQueryOptions blockingOptions = new BlockingQueryOptions()
            .setWait(HEALTH_WATCH_TIMEOUT_SECONDS + "s");

        if (lastHealthIndex > 0) {
            blockingOptions.setIndex(lastHealthIndex);
        }

        CheckQueryOptions queryOptions = new CheckQueryOptions()
            .setBlockingOptions(blockingOptions);

        // Query all health checks using /v1/health/state/any (like old ChangeListenerHealthServices)
        consulClient.healthStateWithOptions(HealthState.ANY, queryOptions)
            .onSuccess(checkList -> {
                // Update the index for the next blocking query
                long newIndex = checkList.getIndex();
                long previousIndex = lastHealthIndex;

                if (newIndex != previousIndex) {
                    lastHealthIndex = newIndex;
                    if (previousIndex > 0) {
                        // Index changed - health state has changed, trigger reload
                        log.info("Health state changed (index {} -> {}), reloading services",
                            previousIndex, newIndex);
                        loadAllServices();
                    }
                }

                // Schedule next watch iteration (small delay to prevent tight loop on errors)
                if (healthStateWatchActive) {
                    vertx.setTimer(100, id -> doHealthStateWatch());
                }
            })
            .onFailure(err -> {
                if (healthStateWatchActive) {
                    // Log warning and retry after a delay
                    log.warn("Health state watch error: {}, retrying in 5s", err.getMessage());
                    vertx.setTimer(5000, id -> doHealthStateWatch());
                }
            });
    }

    /**
     * Stop the health state watch.
     */
    private void stopHealthStateWatch() {
        healthStateWatchActive = false;
        log.info("Stopped health state watch");
    }

    /**
     * Start background refresh of the service catalog (fallback).
     */
    public void startBackgroundRefresh() {
        startBackgroundRefresh(DEFAULT_REFRESH_INTERVAL_MS);
    }

    /**
     * Start background refresh with custom interval (fallback).
     */
    public void startBackgroundRefresh(long intervalMs) {
        if (refreshTimerId >= 0) {
            log.warn("Background refresh already running");
            return;
        }

        // Initial load
        loadAllServices();

        // Schedule periodic refresh
        refreshTimerId = vertx.setPeriodic(intervalMs, id -> {
            log.debug("Background refresh triggered");
            loadAllServices();
        });

        log.info("Background refresh started with interval {}ms", intervalMs);
    }

    /**
     * Stop background refresh.
     */
    public void stopBackgroundRefresh() {
        if (refreshTimerId >= 0) {
            vertx.cancelTimer(refreshTimerId);
            refreshTimerId = -1;
            log.info("Background refresh stopped");
        }
    }

    /**
     * Stop all watches.
     */
    public void stopWatching() {
        // Stop health state watch
        stopHealthStateWatch();

        serviceChangeListeners.clear();
    }

    /**
     * Load all services from Consul using cache rotation.
     * <p>
     * This method creates a new cache, loads all services into it, warms it with
     * the lookup keys from the old cache, and then atomically swaps the caches.
     * This approach minimizes latency during the swap since the new cache is
     * pre-warmed with the same lookups that were used in the old cache.
     */
    public Future<Void> loadAllServices() {
        if (!loading.compareAndSet(false, true)) {
            log.debug("Load already in progress, skipping");
            return Future.succeededFuture();
        }

        long rotationIndex = cacheRotationIndex.incrementAndGet();
        log.info("Loading services from Consul (cache rotation #{})", rotationIndex);

        // Create a new cache for this rotation
        ConsulServiceCache newCache = new ConsulServiceCache(reachableEnvironments);

        // Load coordinates in parallel with service catalog
        Future<Void> coordinatesFuture = loadCoordinates();

        Future<Void> servicesFuture = consulClient.catalogServices()
            .compose(serviceList -> {
                List<String> serviceNames = serviceList.getList().stream()
                    .map(Service::getName)
                    .filter(name -> !"consul".equals(name)) // Skip Consul itself
                    .collect(Collectors.toList());

                log.debug("Found {} services in catalog", serviceNames.size());

                // Load health info for each service into the NEW cache
                return loadServiceHealthBatchedIntoCache(serviceNames, newCache);
            });

        return Future.all(coordinatesFuture, servicesFuture)
            .<Void>mapEmpty()
            .onSuccess(v -> {
                // Get the old cache and its accessed lookup keys
                ConsulServiceCache oldCache = serviceCacheRef.get();
                Set<ConsulServiceCache.ServiceNameWithTags> accessedKeys = oldCache.getAccessedLookupKeys();

                // Warm the new cache with the same lookups as the old cache
                if (!accessedKeys.isEmpty()) {
                    newCache.warmCache(accessedKeys);
                }

                // Atomically swap the caches
                serviceCacheRef.set(newCache);

                lastLoadTime = Instant.now();
                log.info("Cache rotation #{} complete: {} services, {} instances, {} lookup keys warmed",
                    rotationIndex,
                    newCache.getServiceNames().size(),
                    newCache.getTotalInstanceCount(),
                    accessedKeys.size());
            })
            .onFailure(err -> {
                log.error("Failed to load services from Consul (rotation #{})", rotationIndex, err);
            })
            .andThen(ar -> loading.set(false));
    }

    /**
     * Load health information for multiple services into a specific cache.
     */
    private Future<Void> loadServiceHealthBatchedIntoCache(List<String> serviceNames, ConsulServiceCache targetCache) {
        if (serviceNames.isEmpty()) {
            return Future.succeededFuture();
        }

        log.info("Loading health for {} services in batches of {}", serviceNames.size(), HEALTH_BATCH_SIZE);

        // Use a promise to track overall completion
        io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();

        // Process in batches with delays
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < serviceNames.size(); i += HEALTH_BATCH_SIZE) {
            batches.add(serviceNames.subList(i, Math.min(i + HEALTH_BATCH_SIZE, serviceNames.size())));
        }

        // Chain batches sequentially with delays
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < batches.size(); i++) {
            final List<String> batch = batches.get(i);
            final int batchNum = i + 1;

            if (i == 0) {
                // First batch runs immediately
                chain = chain.compose(v -> {
                    log.debug("Loading health batch 1 ({} services)", batch.size());
                    List<Future<Void>> futures = batch.stream()
                        .map(name -> loadServiceHealthIntoCache(name, targetCache))
                        .collect(Collectors.toList());
                    return Future.all(futures).mapEmpty();
                });
            } else {
                // Subsequent batches wait for delay
                chain = chain.compose(v -> {
                    io.vertx.core.Promise<Void> delayPromise = io.vertx.core.Promise.promise();
                    vertx.setTimer(HEALTH_BATCH_DELAY_MS, timerId -> {
                        log.debug("Loading health batch {} ({} services)", batchNum, batch.size());
                        List<Future<Void>> futures = batch.stream()
                            .map(name -> loadServiceHealthIntoCache(name, targetCache))
                            .collect(Collectors.toList());
                        Future.all(futures).<Void>mapEmpty()
                            .onComplete(delayPromise);
                    });
                    return delayPromise.future();
                });
            }
        }

        chain.onComplete(promise);
        return promise.future();
    }

    /**
     * Load health information for a specific service into a specific cache.
     */
    private Future<Void> loadServiceHealthIntoCache(String serviceName, ConsulServiceCache targetCache) {
        ServiceQueryOptions options = new ServiceQueryOptions()
            .setNear(AGENT); // Sort by proximity to local agent

        return consulClient.healthServiceNodesWithOptions(serviceName, false, options)
            .map(serviceEntryList -> {
                List<ConsulService> services = serviceEntryList.getList().stream()
                    .map(ConsulService::new)
                    .collect(Collectors.toList());

                targetCache.updateServices(serviceName, services);
                return (Void) null;
            })
            .onFailure(err -> {
                log.warn("Failed to load health for service '{}': {}", serviceName, err.getMessage());
            })
            .mapEmpty();
    }

    /**
     * Load node coordinates from Consul for RTT-based routing.
     * Also determines the local agent's node name and coordinate.
     */
    public Future<Void> loadCoordinates() {
        return consulClient.coordinateNodes()
            .compose(coordinateList -> {
                // Update the coordinate cache
                nodeCoordinates.clear();
                for (Coordinate coord : coordinateList.getList()) {
                    if (coord.getNode() != null) {
                        nodeCoordinates.put(coord.getNode(), coord);
                    }
                }
                log.debug("Loaded coordinates for {} nodes", nodeCoordinates.size());

                // Get the local agent's node name
                return consulClient.agentInfo();
            })
            .map(agentInfo -> {
                // Extract node name from agent info
                // The agent info contains a "Config" section with "NodeName"
                if (agentInfo != null) {
                    localAgentNode = agentInfo.getString("NodeName");
                    if (localAgentNode == null) {
                        // Try alternate path
                        io.vertx.core.json.JsonObject config = agentInfo.getJsonObject("Config");
                        if (config != null) {
                            localAgentNode = config.getString("NodeName");
                        }
                    }
                    if (localAgentNode != null) {
                        localAgentCoordinate = nodeCoordinates.get(localAgentNode);
                        log.info("Local agent node: {}, coordinate available: {}",
                            localAgentNode, localAgentCoordinate != null);
                    }
                }
                return (Void) null;
            })
            .onFailure(err -> {
                log.warn("Failed to load coordinates: {}", err.getMessage());
            })
            .mapEmpty();
    }

    /**
     * Get the coordinate for a specific node.
     *
     * @param nodeName the node name
     * @return the coordinate, or null if not found
     */
    public Coordinate getNodeCoordinate(String nodeName) {
        return nodeCoordinates.get(nodeName);
    }

    /**
     * Get the local agent's coordinate.
     *
     * @return the local agent's coordinate, or null if not available
     */
    public Coordinate getLocalAgentCoordinate() {
        return localAgentCoordinate;
    }

    /**
     * Get the local agent's node name.
     *
     * @return the local agent's node name, or null if not available
     */
    public String getLocalAgentNode() {
        return localAgentNode;
    }

    /**
     * Calculate the estimated RTT in milliseconds from the local agent to a service instance.
     *
     * @param service the service instance
     * @return estimated RTT in milliseconds, or -1 if coordinates are not available
     */
    public float getEstimatedRtt(ConsulService service) {
        if (localAgentCoordinate == null) {
            return -1;
        }
        Coordinate nodeCoord = nodeCoordinates.get(service.getNodeName());
        if (nodeCoord == null) {
            return -1;
        }
        return CoordinateUtil.estimateRtt(localAgentCoordinate, nodeCoord);
    }

    /**
     * Get a service by name with optional tag filtering.
     *
     * @param serviceName the service name
     * @param tags        tags to filter by (can be null or empty for default tags)
     * @return the first matching service, or empty if not found
     */
    public Optional<ConsulService> getService(String serviceName, List<String> tags) {
        List<String> effectiveTags = (tags != null && !tags.isEmpty()) ? tags : defaultTags;

        // Check environment reachability
        Optional<String> envTag = effectiveTags.stream()
            .filter(t -> t.startsWith("env:"))
            .findFirst();

        if (envTag.isPresent()) {
            String env = envTag.get().substring(4);
            if (!serviceCacheRef.get().isEnvironmentReachable(env)) {
                log.warn("Environment '{}' is not reachable", env);
                return Optional.empty();
            }
        }

        return serviceCacheRef.get().getService(serviceName, effectiveTags);
    }

    /**
     * Get all instances of a service with optional tag filtering.
     */
    public List<ConsulService> getServices(String serviceName, List<String> tags) {
        List<String> effectiveTags = (tags != null && !tags.isEmpty()) ? tags : defaultTags;
        return serviceCacheRef.get().getServices(serviceName, effectiveTags);
    }

    /**
     * Get the current service cache for direct access or monitoring.
     * Note: The cache may be swapped during rotation; callers should not hold
     * long-lived references to the returned cache.
     */
    public ConsulServiceCache getServiceCache() {
        return serviceCacheRef.get();
    }

    /**
     * Get the cache rotation index (number of times the cache has been rotated).
     */
    public long getCacheRotationIndex() {
        return cacheRotationIndex.get();
    }

    /**
     * Get the default environment.
     */
    public String getDefaultEnvironment() {
        return defaultEnvironment;
    }

    /**
     * Get the default tags.
     */
    public List<String> getDefaultTags() {
        return Collections.unmodifiableList(defaultTags);
    }

    /**
     * Get the configured datacenter.
     */
    public String getDatacenter() {
        return consulClientOptions.getDc();
    }

    /**
     * Get the set of reachable environments.
     */
    public Set<String> getReachableEnvironments() {
        return serviceCacheRef.get().getReachableEnvironments();
    }

    /**
     * Get the names of all cached services.
     */
    public Set<String> getServiceNames() {
        return serviceCacheRef.get().getServiceNames();
    }

    /**
     * Get the total number of service instances across all services.
     */
    public int getTotalInstanceCount() {
        return serviceCacheRef.get().getTotalInstanceCount();
    }

    /**
     * Get the last load time.
     */
    public Instant getLastLoadTime() {
        return lastLoadTime;
    }

    /**
     * Check if the registry is currently loading.
     */
    public boolean isLoading() {
        return loading.get();
    }

    /**
     * Check if the health state watch is active.
     */
    public boolean isHealthStateWatchActive() {
        return healthStateWatchActive;
    }

    /**
     * Close the registry and release resources.
     */
    public void close() {
        stopWatching();
        stopBackgroundRefresh();
        consulClient.close();
        log.info("ConsulServiceRegistry closed");
    }

    public void addServiceChangeListener(String serviceName, ServiceChangeListener listener) {
        serviceChangeListeners
            .computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet())
            .add(listener);
    }

    public void removeServiceChangeListener(String serviceName, ServiceChangeListener listener) {
        Set<ServiceChangeListener> listeners = serviceChangeListeners.get(serviceName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                serviceChangeListeners.remove(serviceName);
            }
        }
    }

    private void notifyServiceChange(String serviceName) {
        Set<ServiceChangeListener> listeners = serviceChangeListeners.get(serviceName);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (ServiceChangeListener listener : listeners) {
            try {
                listener.onServiceInstancesChanged(serviceName);
            } catch (Exception e) {
                log.warn("Service change listener error for {}", serviceName, e);
            }
        }
    }

    /**
     * Get registry status as a map.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("defaultEnvironment", defaultEnvironment);
        status.put("defaultTags", defaultTags);
        status.put("loading", loading.get());
        status.put("lastLoadTime", lastLoadTime != null ? lastLoadTime.toString() : null);
        status.put("serviceCount", serviceCacheRef.get().getServiceNames().size());
        status.put("totalInstances", serviceCacheRef.get().getTotalInstanceCount());
        status.put("healthStateWatchActive", healthStateWatchActive);
        return status;
    }
}
