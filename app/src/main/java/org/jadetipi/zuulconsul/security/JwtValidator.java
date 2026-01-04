package org.jadetipi.zuulconsul.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JWT validator that uses JWKS (JSON Web Key Set) for signature verification.
 * <p>
 * The JWKS URL is configurable via the ZUUL_JWKS_URL environment variable.
 * Keys are cached and refreshed automatically when validation fails or after a TTL.
 */
public class JwtValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);

    private static final String ENV_JWKS_URL = "ZUUL_JWKS_URL";
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(15);

    private final String jwksUrl;
    private final AtomicReference<CachedJwks> cachedJwks = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * Create a JwtValidator with the JWKS URL from environment.
     * If ZUUL_JWKS_URL is not set, validation is disabled (all requests allowed).
     */
    public JwtValidator() {
        this(System.getenv(ENV_JWKS_URL));
    }

    /**
     * Create a JwtValidator with a specific JWKS URL.
     *
     * @param jwksUrl the JWKS URL, or null to disable validation
     */
    public JwtValidator(String jwksUrl) {
        this.jwksUrl = jwksUrl;
        if (jwksUrl != null && !jwksUrl.isEmpty()) {
            log.info("JWT validation enabled with JWKS URL: {}", jwksUrl);
        } else {
            log.warn("JWT validation DISABLED - {} not set", ENV_JWKS_URL);
        }
    }

    /**
     * Check if JWT validation is enabled.
     */
    public boolean isEnabled() {
        return jwksUrl != null && !jwksUrl.isEmpty();
    }

    /**
     * Validate a JWT token.
     *
     * @param token the JWT token (without "Bearer " prefix)
     * @return ValidationResult indicating success or failure with reason
     */
    public ValidationResult validate(String token) {
        if (!isEnabled()) {
            return ValidationResult.success(null);
        }

        if (token == null || token.isEmpty()) {
            return ValidationResult.failure("Missing token");
        }

        try {
            JWTClaimsSet claims = validateToken(token);
            return ValidationResult.success(claims);
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return ValidationResult.failure(e.getMessage());
        }
    }

    /**
     * Validate the token and return claims.
     */
    private JWTClaimsSet validateToken(String token) throws Exception {
        ConfigurableJWTProcessor<SecurityContext> processor = createProcessor();

        try {
            return processor.process(token, null);
        } catch (BadJOSEException | JOSEException e) {
            // Signature validation failed - try refreshing JWKS once
            log.debug("Token validation failed, attempting JWKS refresh");
            refreshJwks();
            processor = createProcessor();
            return processor.process(token, null);
        }
    }

    /**
     * Create a JWT processor with current JWKS.
     */
    private ConfigurableJWTProcessor<SecurityContext> createProcessor() throws Exception {
        JWKSet jwkSet = getJwks();

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(jwkSet);

        // Support common algorithms
        JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(
            JWSAlgorithm.RS256, keySource);
        processor.setJWSKeySelector(keySelector);

        return processor;
    }

    /**
     * Get JWKS, using cache if valid.
     */
    private JWKSet getJwks() throws Exception {
        CachedJwks cached = cachedJwks.get();

        if (cached != null && !cached.isExpired()) {
            return cached.jwkSet;
        }

        return refreshJwks();
    }

    /**
     * Refresh JWKS from the remote URL.
     */
    private JWKSet refreshJwks() throws Exception {
        if (!refreshLock.tryLock()) {
            // Another thread is refreshing, wait for it
            refreshLock.lock();
            try {
                CachedJwks cached = cachedJwks.get();
                if (cached != null && !cached.isExpired()) {
                    return cached.jwkSet;
                }
            } finally {
                refreshLock.unlock();
            }
        }

        try {
            log.info("Fetching JWKS from: {}", jwksUrl);
            JWKSet jwkSet = JWKSet.load(new URL(jwksUrl));
            cachedJwks.set(new CachedJwks(jwkSet, Instant.now().plus(JWKS_CACHE_TTL)));
            log.info("JWKS loaded successfully, {} keys found", jwkSet.getKeys().size());
            return jwkSet;
        } catch (IOException | ParseException e) {
            log.error("Failed to fetch JWKS from {}: {}", jwksUrl, e.getMessage());
            throw new RuntimeException("Failed to fetch JWKS: " + e.getMessage(), e);
        } finally {
            if (refreshLock.isHeldByCurrentThread()) {
                refreshLock.unlock();
            }
        }
    }

    /**
     * Get the configured JWKS URL.
     */
    public String getJwksUrl() {
        return jwksUrl;
    }

    /**
     * Cached JWKS with expiration.
     */
    private static class CachedJwks {
        final JWKSet jwkSet;
        final Instant expiresAt;

        CachedJwks(JWKSet jwkSet, Instant expiresAt) {
            this.jwkSet = jwkSet;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Result of token validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String error;
        private final JWTClaimsSet claims;

        private ValidationResult(boolean valid, String error, JWTClaimsSet claims) {
            this.valid = valid;
            this.error = error;
            this.claims = claims;
        }

        public static ValidationResult success(JWTClaimsSet claims) {
            return new ValidationResult(true, null, claims);
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, error, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getError() {
            return error;
        }

        public JWTClaimsSet getClaims() {
            return claims;
        }
    }
}
