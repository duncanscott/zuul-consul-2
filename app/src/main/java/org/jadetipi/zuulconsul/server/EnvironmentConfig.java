package org.jadetipi.zuulconsul.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps environment variables to system properties for Archaius configuration.
 * <p>
 * This class must be called before ConfigurationManager loads properties
 * so that environment variables take precedence over application.properties.
 */
public final class EnvironmentConfig {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentConfig.class);

    /**
     * Mapping of environment variable names to property names.
     */
    private static final Map<String, String> ENV_TO_PROPERTY = new LinkedHashMap<>();

    static {
        // Server configuration
        ENV_TO_PROPERTY.put("ZUUL_SERVER_PORT", "zuul.server.port.main");

        // Ribbon/connection pool settings
        ENV_TO_PROPERTY.put("ZUUL_RIBBON_CONNECT_TIMEOUT", "zuul.ribbon.ConnectTimeout");
        ENV_TO_PROPERTY.put("ZUUL_RIBBON_READ_TIMEOUT", "zuul.ribbon.ReadTimeout");
        ENV_TO_PROPERTY.put("ZUUL_RIBBON_MAX_AUTO_RETRIES", "zuul.ribbon.MaxAutoRetries");
        ENV_TO_PROPERTY.put("ZUUL_RIBBON_MAX_AUTO_RETRIES_NEXT_SERVER", "zuul.ribbon.MaxAutoRetriesNextServer");

        // Consul refresh interval
        ENV_TO_PROPERTY.put("ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES", "zuul.consul.refresh.interval.minutes");
    }

    private EnvironmentConfig() {
        // Utility class
    }

    /**
     * Initialize configuration from environment variables.
     * <p>
     * This sets system properties from environment variables, which will then
     * be picked up by Archaius ConfigurationManager when it loads properties.
     * Environment variables take precedence over application.properties values.
     */
    public static void init() {
        log.debug("Initializing configuration from environment variables");

        for (Map.Entry<String, String> entry : ENV_TO_PROPERTY.entrySet()) {
            String envVar = entry.getKey();
            String propertyName = entry.getValue();

            String envValue = System.getenv(envVar);
            if (envValue != null && !envValue.isEmpty()) {
                System.setProperty(propertyName, envValue);
                log.info("Set {} from environment variable {}", propertyName, envVar);
            }
        }
    }

    /**
     * Get the configured refresh interval in milliseconds.
     *
     * @return refresh interval in milliseconds (default: 5 minutes)
     */
    public static long getRefreshIntervalMs() {
        String envValue = System.getenv("ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES");
        if (envValue != null && !envValue.isEmpty()) {
            try {
                return Long.parseLong(envValue) * 60 * 1000;
            } catch (NumberFormatException e) {
                log.warn("Invalid ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES value: {}, using default", envValue);
            }
        }

        String propValue = System.getProperty("zuul.consul.refresh.interval.minutes");
        if (propValue != null && !propValue.isEmpty()) {
            try {
                return Long.parseLong(propValue) * 60 * 1000;
            } catch (NumberFormatException e) {
                log.warn("Invalid zuul.consul.refresh.interval.minutes value: {}, using default", propValue);
            }
        }

        // Default: 5 minutes
        return 5 * 60 * 1000;
    }
}
