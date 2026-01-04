package org.jadetipi.zuulconsul.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netflix.zuul.filters.http.HttpSyncEndpoint;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.message.http.HttpResponseMessageImpl;
import org.jadetipi.zuulconsul.consul.ConsulService;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;
import org.jadetipi.zuulconsul.security.JwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Endpoint that returns service registry information at the root path (/).
 * <p>
 * This endpoint is secured with JWT bearer token validation when ZUUL_JWKS_URL
 * is configured. The token is validated against the JWKS endpoint.
 * <p>
 * Returns JSON containing:
 * <ul>
 *   <li>Application version, git commit, and build date</li>
 *   <li>Consul datacenter</li>
 *   <li>Server start time</li>
 *   <li>Reachable environments</li>
 *   <li>Registered services with health status</li>
 * </ul>
 */
public class ServiceRegistryEndpoint extends HttpSyncEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistryEndpoint.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private static final String BEARER_PREFIX = "Bearer ";

    private final ConsulServiceRegistry registry;
    private final JwtValidator jwtValidator;
    private final Instant startTime;
    private final Properties appProperties;

    public ServiceRegistryEndpoint(ConsulServiceRegistry registry, JwtValidator jwtValidator) {
        this.registry = registry;
        this.jwtValidator = jwtValidator;
        this.startTime = Instant.now();
        this.appProperties = loadAppProperties();
    }

    @Override
    public HttpResponseMessage apply(HttpRequestMessage request) {
        // Validate JWT token if security is enabled
        if (jwtValidator.isEnabled()) {
            JwtValidator.ValidationResult authResult = validateRequest(request);
            if (!authResult.isValid()) {
                return createUnauthorizedResponse(request, authResult.getError());
            }
        }

        try {
            String json = buildRegistryJson();

            HttpResponseMessage response = new HttpResponseMessageImpl(
                request.getContext(), request, 200);
            response.getHeaders().set("Content-Type", "application/json; charset=utf-8");
            response.setBody(json.getBytes(StandardCharsets.UTF_8));

            return response;
        } catch (Exception e) {
            log.error("Error building service registry response", e);

            HttpResponseMessage response = new HttpResponseMessageImpl(
                request.getContext(), request, 500);
            response.getHeaders().set("Content-Type", "application/json");
            response.setBody("{\"error\": \"Internal server error\"}".getBytes(StandardCharsets.UTF_8));

            return response;
        }
    }

    /**
     * Validate the JWT bearer token from the Authorization header.
     */
    private JwtValidator.ValidationResult validateRequest(HttpRequestMessage request) {
        String authHeader = request.getHeaders().getFirst("Authorization");

        if (authHeader == null || authHeader.isEmpty()) {
            return JwtValidator.ValidationResult.failure("Missing Authorization header");
        }

        if (!authHeader.startsWith(BEARER_PREFIX)) {
            return JwtValidator.ValidationResult.failure("Invalid Authorization header format");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        return jwtValidator.validate(token);
    }

    /**
     * Create a 401 Unauthorized response.
     */
    private HttpResponseMessage createUnauthorizedResponse(HttpRequestMessage request, String error) {
        log.warn("Unauthorized access to service registry: {}", error);

        HttpResponseMessage response = new HttpResponseMessageImpl(
            request.getContext(), request, 401);
        response.getHeaders().set("Content-Type", "application/json");
        response.getHeaders().set("WWW-Authenticate", "Bearer realm=\"zuul-consul\"");

        String json = String.format("{\"error\": \"Unauthorized\", \"message\": \"%s\"}",
            error.replace("\"", "\\\""));
        response.setBody(json.getBytes(StandardCharsets.UTF_8));

        return response;
    }

    private String buildRegistryJson() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        // Application info
        root.put("version", appProperties.getProperty("app.version", "unknown"));
        root.put("name", appProperties.getProperty("app.name", "zuul-consul"));
        root.put("git_commit", appProperties.getProperty("app.git.commit", "unknown"));
        root.put("build_date", appProperties.getProperty("app.build.date", "unknown"));

        // Runtime info
        root.put("datacenter", registry.getDatacenter());
        root.put("start_date", DATE_FORMAT.format(startTime));

        // Environment info
        Set<String> reachableEnvs = registry.getReachableEnvironments();
        ArrayNode envsArray = root.putArray("reachable_environments");
        reachableEnvs.forEach(envsArray::add);

        List<String> defaultTags = registry.getDefaultTags();
        ArrayNode defaultTagsArray = root.putArray("default_tags");
        defaultTags.forEach(defaultTagsArray::add);

        // Service cache info
        ObjectNode cacheNode = root.putObject("service_cache");
        Set<String> serviceNames = registry.getServiceNames();
        cacheNode.put("service_count", serviceNames.size());
        cacheNode.put("instance_count", registry.getTotalInstanceCount());

        // Services
        ObjectNode servicesNode = cacheNode.putObject("services");
        for (String serviceName : serviceNames) {
            List<ConsulService> instances = registry.getServices(serviceName, List.of());
            if (!instances.isEmpty()) {
                ArrayNode instancesArray = servicesNode.putArray(serviceName);
                for (ConsulService instance : instances) {
                    ObjectNode instanceNode = instancesArray.addObject();
                    instanceNode.put("id", instance.getId());
                    instanceNode.put("address", instance.getAddress());
                    instanceNode.put("port", instance.getPort());
                    instanceNode.put("healthy", instance.isHealthy());
                    instanceNode.put("node", instance.getNodeName());

                    ArrayNode tagsArray = instanceNode.putArray("tags");
                    instance.getTags().forEach(tagsArray::add);
                }
            }
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private Properties loadAppProperties() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("app.properties")) {
            if (is != null) {
                props.load(is);
            } else {
                log.warn("app.properties not found on classpath");
            }
        } catch (IOException e) {
            log.warn("Failed to load app.properties", e);
        }
        return props;
    }
}
