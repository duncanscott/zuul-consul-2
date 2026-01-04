package org.jadetipi.zuulconsul.functional

import groovy.transform.CompileStatic

/**
 * Configuration for functional tests.
 * Uses environment variables with sensible defaults for local Docker testing.
 */
@CompileStatic
class FunctionalTestConfig {

    /** Consul agent URL for service discovery */
    static final String CONSUL_URL = System.getenv('CONSUL_URL') ?: 'http://localhost:8500'

    /** Zuul gateway URL (direct access, when running locally) */
    static final String ZUUL_URL = System.getenv('ZUUL_URL') ?: 'http://localhost:9091'

    /** Nginx proxy URL (HTTP) - for testing nginx configuration */
    static final String NGINX_HTTP_URL = System.getenv('NGINX_HTTP_URL') ?: 'http://localhost:8080'

    /** Nginx proxy URL (HTTPS) - for testing nginx SSL/TLS configuration */
    static final String NGINX_HTTPS_URL = System.getenv('NGINX_HTTPS_URL') ?: 'https://localhost:8443'

    /** Nginx status URL - for health checks and metrics */
    static final String NGINX_STATUS_URL = System.getenv('NGINX_STATUS_URL') ?: 'http://localhost:8888'

    /** CouchDB URL for stats storage */
    static final String COUCHDB_URL = System.getenv('COUCHDB_URL') ?: 'http://localhost:5994'

    /** CouchDB database name */
    static final String COUCHDB_DATABASE = 'zuul-consul'

    /** CouchDB credentials */
    static final String COUCHDB_USER = System.getenv('COUCHDB_USER') ?: 'admin'
    static final String COUCHDB_PASSWORD = System.getenv('COUCHDB_PASSWORD') ?: 'password'

    /** Default environment for routing */
    static final String DEFAULT_ENVIRONMENT = System.getenv('ZUUL_DEFAULT_ENVIRONMENT') ?: 'dev'

    /** Reachable environments (colon-separated) */
    static final String REACHABLE_ENVIRONMENTS = System.getenv('ZUUL_REACHABLE_ENVIRONMENTS') ?: 'dev:test'

    /** Connection timeout in milliseconds */
    static final int CONNECT_TIMEOUT_MS = 5000

    /** Read timeout in milliseconds */
    static final int READ_TIMEOUT_MS = 30000

    /** Maximum retries for connection attempts */
    static final int MAX_RETRIES = 3

    /** Delay between retries in milliseconds */
    static final int RETRY_DELAY_MS = 1000

    /** Whether to trust self-signed certificates (for testing) */
    static final boolean TRUST_SELF_SIGNED_CERTS = true
}
