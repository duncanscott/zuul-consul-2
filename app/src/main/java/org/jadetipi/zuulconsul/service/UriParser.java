package org.jadetipi.zuulconsul.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Parses incoming request URIs to extract service name, tags, and remaining path.
 * <p>
 * URI pattern: /{tag1:value1}/{tag2:value2}/{serviceName}/{remainingPath}
 * <p>
 * Examples:
 * <ul>
 *   <li>/env:dev/pps-esp-entity/api/users → service=pps-esp-entity, tags=[env:dev], path=/api/users</li>
 *   <li>/env:dev/version:v2/my-service/api → service=my-service, tags=[env:dev, version:v2], path=/api</li>
 *   <li>/my-service/api/users → service=my-service, tags=[], path=/api/users</li>
 * </ul>
 */
public class UriParser {

    /**
     * Result of parsing a URI.
     */
    public static class ParsedUri {
        private final String serviceName;
        private final List<String> tags;
        private final String remainingPath;
        private final String originalUri;

        public ParsedUri(String serviceName, List<String> tags, String remainingPath, String originalUri) {
            this.serviceName = serviceName;
            this.tags = tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : Collections.emptyList();
            this.remainingPath = remainingPath != null ? remainingPath : "";
            this.originalUri = originalUri;
        }

        public String getServiceName() {
            return serviceName;
        }

        public List<String> getTags() {
            return tags;
        }

        public String getRemainingPath() {
            return remainingPath;
        }

        public String getOriginalUri() {
            return originalUri;
        }

        /**
         * Check if this is a valid parsed URI (has a service name).
         */
        public boolean isValid() {
            return serviceName != null && !serviceName.isEmpty();
        }

        /**
         * Check if this is the root path (no service name).
         */
        public boolean isRootPath() {
            return serviceName == null || serviceName.isEmpty();
        }

        @Override
        public String toString() {
            return String.format("ParsedUri{service=%s, tags=%s, path=%s}",
                serviceName, tags, remainingPath);
        }
    }

    /**
     * Parse a URI into its components.
     *
     * @param uri the URI to parse (e.g., "/env:dev/my-service/api/users")
     * @return parsed URI result
     */
    public ParsedUri parse(String uri) {
        if (uri == null || uri.isEmpty() || uri.equals("/")) {
            return new ParsedUri(null, Collections.emptyList(), "/", uri);
        }

        // Remove leading slash and split by /
        String normalizedUri = uri.startsWith("/") ? uri.substring(1) : uri;
        String[] parts = normalizedUri.split("/");

        if (parts.length == 0) {
            return new ParsedUri(null, Collections.emptyList(), "/", uri);
        }

        List<String> tags = new ArrayList<>();
        String serviceName = null;
        int serviceIndex = -1;

        // Process parts: tags contain ":", service name does not
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }

            if (isTag(part)) {
                tags.add(part);
            } else {
                // First non-tag part is the service name
                serviceName = part;
                serviceIndex = i;
                break;
            }
        }

        // Build remaining path from parts after service name
        String remainingPath = buildRemainingPath(parts, serviceIndex);

        return new ParsedUri(serviceName, tags, remainingPath, uri);
    }

    /**
     * Check if a path part is a tag (contains ":").
     */
    private boolean isTag(String part) {
        return part.contains(":");
    }

    /**
     * Build the remaining path from parts after the service name.
     */
    private String buildRemainingPath(String[] parts, int serviceIndex) {
        if (serviceIndex < 0 || serviceIndex >= parts.length - 1) {
            return "/";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = serviceIndex + 1; i < parts.length; i++) {
            sb.append("/").append(parts[i]);
        }

        return sb.length() > 0 ? sb.toString() : "/";
    }

    /**
     * Build a backend URI by combining service base URI with remaining path.
     *
     * @param serviceUri    the service base URI (e.g., "http://10.0.1.5:8080/api")
     * @param remainingPath the remaining path from the original request (e.g., "/users/123")
     * @return combined URI
     */
    public String buildBackendUri(String serviceUri, String remainingPath) {
        Objects.requireNonNull(serviceUri, "serviceUri must not be null");

        // Remove trailing slash from service URI
        String base = serviceUri.endsWith("/")
            ? serviceUri.substring(0, serviceUri.length() - 1)
            : serviceUri;

        // Ensure remaining path starts with /
        String path = (remainingPath == null || remainingPath.isEmpty())
            ? ""
            : (remainingPath.startsWith("/") ? remainingPath : "/" + remainingPath);

        return base + path;
    }
}
