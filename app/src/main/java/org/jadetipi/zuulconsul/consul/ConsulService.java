package org.jadetipi.zuulconsul.consul;

import io.vertx.ext.consul.CheckStatus;
import io.vertx.ext.consul.Service;
import io.vertx.ext.consul.ServiceEntry;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Wraps a Consul ServiceEntry with computed URI and parsed tags.
 * This is equivalent to the ConsulService class in the Zuul 1 version.
 */
public class ConsulService {

    private static final String CONTEXT_ROOT_PREFIX = "context-root!";
    private static final String DOCS_PREFIX = "docs!";

    private final ServiceEntry serviceEntry;
    private final URI uri;
    private final String contextRoot;
    private final String docsUrl;
    private final List<String> tags;
    private final CheckStatus healthStatus;
    private final String nodeName;

    public ConsulService(ServiceEntry serviceEntry) {
        this.serviceEntry = Objects.requireNonNull(serviceEntry, "serviceEntry must not be null");
        // Filter out null tags to prevent NPE in downstream sorting/processing
        List<String> rawTags = serviceEntry.getService().getTags();
        this.tags = rawTags != null
            ? rawTags.stream().filter(Objects::nonNull).collect(Collectors.toList())
            : Collections.emptyList();
        this.contextRoot = extractContextRoot();
        this.docsUrl = extractDocsUrl();
        this.uri = buildUri();
        this.healthStatus = serviceEntry.aggregatedStatus();
        this.nodeName = serviceEntry.getNode().getName();
    }

    private String extractContextRoot() {
        return tags.stream()
            .filter(tag -> tag.startsWith(CONTEXT_ROOT_PREFIX))
            .map(tag -> tag.substring(CONTEXT_ROOT_PREFIX.length()))
            .findFirst()
            .orElse("");
    }

    private String extractDocsUrl() {
        return tags.stream()
            .filter(tag -> tag.startsWith(DOCS_PREFIX))
            .map(tag -> tag.substring(DOCS_PREFIX.length()))
            .findFirst()
            .orElse(null);
    }

    private URI buildUri() {
        Service service = serviceEntry.getService();
        String address = service.getAddress();

        // Fall back to node address if service address is empty
        if (address == null || address.isEmpty()) {
            address = serviceEntry.getNode().getAddress();
        }

        int port = service.getPort();
        String path = contextRoot.isEmpty() ? "" : contextRoot;

        // Ensure path starts with /
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }

        // Detect protocol from address (e.g., "https://prospero.jgi.doe.gov")
        String protocol = "http";
        if (address.startsWith("https://")) {
            protocol = "https";
            address = address.substring(8); // Remove "https://"
        } else if (address.startsWith("http://")) {
            address = address.substring(7); // Remove "http://"
        }

        return URI.create(String.format("%s://%s:%d%s", protocol, address, port, path));
    }

    public ServiceEntry getServiceEntry() {
        return serviceEntry;
    }

    public Service getService() {
        return serviceEntry.getService();
    }

    public String getName() {
        return serviceEntry.getService().getName();
    }

    public String getId() {
        return serviceEntry.getService().getId();
    }

    public URI getUri() {
        return uri;
    }

    public String getAddress() {
        return uri.getHost();
    }

    public int getPort() {
        return uri.getPort();
    }

    public String getContextRoot() {
        return contextRoot;
    }

    public String getDocsUrl() {
        return docsUrl;
    }

    public List<String> getTags() {
        return tags;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /**
     * Get the aggregated health status of this service instance.
     */
    public CheckStatus getHealthStatus() {
        return healthStatus;
    }

    /**
     * Check if this service instance is healthy (passing all health checks).
     */
    public boolean isHealthy() {
        return healthStatus == CheckStatus.PASSING;
    }

    /**
     * Get the name of the Consul node hosting this service instance.
     * Used for coordinate-based RTT lookups.
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * Check if this service matches all the specified tags.
     */
    public boolean matchesTags(List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        return tags.containsAll(requiredTags);
    }

    @Override
    public String toString() {
        return String.format("ConsulService{name=%s, uri=%s, tags=%s}",
            getName(), uri, tags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsulService that = (ConsulService) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }
}
