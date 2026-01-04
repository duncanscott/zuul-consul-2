package org.jadetipi.zuulconsul.server;

import com.netflix.config.DynamicPropertyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides configuration values from environment variables with fallback to properties.
 * <p>
 * Environment variables take precedence over application.properties values.
 * This class provides type-safe accessors for all configurable values.
 */
public final class EnvironmentConfig {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentConfig.class);

    // Default values
    private static final int DEFAULT_CONNECT_TIMEOUT = 2000;
    private static final int DEFAULT_READ_TIMEOUT = 30000;
    private static final int DEFAULT_MAX_AUTO_RETRIES = 0;
    private static final int DEFAULT_MAX_AUTO_RETRIES_NEXT_SERVER = 1;
    private static final int DEFAULT_REFRESH_INTERVAL_MINUTES = 15;

    private EnvironmentConfig() {
        // Utility class
    }

    /**
     * Get connection timeout in milliseconds.
     */
    public static int getConnectTimeout() {
        return getInt("ZUUL_RIBBON_CONNECT_TIMEOUT", "zuul.ribbon.ConnectTimeout", DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Get read timeout in milliseconds.
     */
    public static int getReadTimeout() {
        return getInt("ZUUL_RIBBON_READ_TIMEOUT", "zuul.ribbon.ReadTimeout", DEFAULT_READ_TIMEOUT);
    }

    /**
     * Get max auto retries on the same server.
     */
    public static int getMaxAutoRetries() {
        return getInt("ZUUL_RIBBON_MAX_AUTO_RETRIES", "zuul.ribbon.MaxAutoRetries", DEFAULT_MAX_AUTO_RETRIES);
    }

    /**
     * Get max auto retries on the next server.
     */
    public static int getMaxAutoRetriesNextServer() {
        return getInt("ZUUL_RIBBON_MAX_AUTO_RETRIES_NEXT_SERVER", "zuul.ribbon.MaxAutoRetriesNextServer", DEFAULT_MAX_AUTO_RETRIES_NEXT_SERVER);
    }

    /**
     * Get the configured refresh interval in milliseconds.
     *
     * @return refresh interval in milliseconds (default: 15 minutes)
     */
    public static long getRefreshIntervalMs() {
        int minutes = getInt("ZUUL_CONSUL_REFRESH_INTERVAL_MINUTES", "zuul.consul.refresh.interval.minutes", DEFAULT_REFRESH_INTERVAL_MINUTES);
        return minutes * 60L * 1000L;
    }

    /**
     * Get an integer configuration value.
     * <p>
     * Resolution order:
     * 1. Environment variable (highest priority)
     * 2. System property / Archaius property
     * 3. Default value
     *
     * @param envVar       environment variable name
     * @param propertyName property name for Archaius lookup
     * @param defaultValue default value if not configured
     * @return the configured value
     */
    public static int getInt(String envVar, String propertyName, int defaultValue) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            try {
                int value = Integer.parseInt(envValue);
                log.debug("Config {} = {} (from env {})", propertyName, value, envVar);
                return value;
            } catch (NumberFormatException e) {
                log.warn("Invalid {} value: '{}', falling back to property/default", envVar, envValue);
            }
        }

        // Fall back to Archaius property (includes system properties and application.properties)
        try {
            int value = DynamicPropertyFactory.getInstance()
                .getIntProperty(propertyName, defaultValue)
                .get();
            if (value != defaultValue) {
                log.debug("Config {} = {} (from property)", propertyName, value);
            }
            return value;
        } catch (Exception e) {
            log.warn("Error reading property {}, using default {}", propertyName, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get a string configuration value.
     *
     * @param envVar       environment variable name
     * @param propertyName property name for Archaius lookup
     * @param defaultValue default value if not configured
     * @return the configured value
     */
    public static String getString(String envVar, String propertyName, String defaultValue) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            log.debug("Config {} = {} (from env {})", propertyName, envValue, envVar);
            return envValue;
        }

        // Fall back to Archaius property
        try {
            String value = DynamicPropertyFactory.getInstance()
                .getStringProperty(propertyName, defaultValue)
                .get();
            if (value != null && !value.equals(defaultValue)) {
                log.debug("Config {} = {} (from property)", propertyName, value);
            }
            return value;
        } catch (Exception e) {
            log.warn("Error reading property {}, using default {}", propertyName, defaultValue);
            return defaultValue;
        }
    }
}
