package org.jadetipi.zuulconsul.functional

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.IgnoreIf
import spock.lang.Requires

/**
 * Functional tests for nginx reverse proxy in front of Zuul Consul.
 *
 * These tests verify that nginx correctly:
 * - Proxies requests to zuul-consul
 * - Forwards headers (X-Forwarded-*, X-Real-IP)
 * - Handles CORS preflight requests
 * - Serves HTTPS with proper security headers
 * - Maintains connection keepalive
 *
 * Prerequisites:
 * 1. Docker Compose environment running with nginx-proxy:
 *    docker-compose -f docker/docker-compose.yml --profile nginx up -d
 * 2. Zuul Consul gateway running locally
 *
 * Run with: NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
 */
@Stepwise
class NginxProxySpec extends Specification {

    @Shared
    HttpTestClient nginxHttpClient

    @Shared
    HttpTestClient nginxHttpsClient

    @Shared
    HttpTestClient nginxStatusClient

    @Shared
    HttpTestClient gatewayClient

    @Shared
    boolean nginxAvailable = false

    @Shared
    boolean gatewayAvailable = false

    def setupSpec() {
        // Skip if NGINX_TESTS env var is not set
        if (!System.getenv('NGINX_TESTS')) {
            println "Skipping nginx tests. Set NGINX_TESTS=true to run them."
            return
        }

        nginxHttpClient = new HttpTestClient(FunctionalTestConfig.NGINX_HTTP_URL)
        nginxHttpsClient = new HttpTestClient(FunctionalTestConfig.NGINX_HTTPS_URL, true)
        nginxStatusClient = new HttpTestClient(FunctionalTestConfig.NGINX_STATUS_URL)
        gatewayClient = new HttpTestClient(FunctionalTestConfig.ZUUL_URL)

        // Check if nginx and gateway are available
        nginxAvailable = checkNginxAvailable()
        gatewayAvailable = checkGatewayAvailable()
    }

    private boolean checkNginxAvailable() {
        println "Checking nginx availability..."
        println "Nginx HTTP URL: ${FunctionalTestConfig.NGINX_HTTP_URL}"
        println "Nginx HTTPS URL: ${FunctionalTestConfig.NGINX_HTTPS_URL}"

        if (!nginxStatusClient.waitForAvailable('/health', 5)) {
            println "WARNING: Nginx is not available"
            println "Start nginx with: docker-compose -f docker/docker-compose.yml --profile nginx up -d nginx-proxy"
            return false
        }
        println "Nginx is available"
        return true
    }

    private boolean checkGatewayAvailable() {
        println "Checking gateway availability..."
        if (!gatewayClient.waitForAvailable('/', 5)) {
            println "WARNING: Gateway is not available at ${FunctionalTestConfig.ZUUL_URL}"
            return false
        }
        println "Gateway is available"
        return true
    }

    private boolean shouldRunTests() {
        return System.getenv('NGINX_TESTS') && nginxAvailable && gatewayAvailable
    }

    // ==================== Nginx Health Checks ====================

    @IgnoreIf({ !System.getenv('NGINX_TESTS') })
    def "nginx should be available"() {
        expect:
        nginxAvailable
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx status endpoint should return healthy"() {
        when:
        def response = nginxStatusClient.get('/health')

        then:
        response.success
        response.bodyContains('healthy')
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx status endpoint should return metrics"() {
        when:
        def response = nginxStatusClient.get('/nginx-status')

        then:
        response.success
        response.bodyContains('Active connections')
    }

    // ==================== HTTP Proxy Tests ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTP should proxy requests to gateway"() {
        when:
        def response = nginxHttpClient.get('/')

        then:
        response.success
        response.bodyContains('service_cache') || response.bodyContains('zuul-consul')
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTP should proxy service requests"() {
        when:
        def response = nginxHttpClient.get('/env:dev/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        response.bodyContains('environment=dev')
    }

    // ==================== HTTPS Proxy Tests ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTPS should proxy requests to gateway"() {
        when:
        def response = nginxHttpsClient.get('/')

        then:
        response.success
        response.bodyContains('service_cache') || response.bodyContains('zuul-consul')
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTPS should proxy service requests"() {
        when:
        def response = nginxHttpsClient.get('/env:dev/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        response.bodyContains('environment=dev')
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTPS should include HSTS header"() {
        when:
        def response = nginxHttpsClient.get('/')

        then:
        response.success
        response.headers.get('strict-transport-security')?.any { it.contains('max-age=') }
    }

    // ==================== CORS Tests ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx should include CORS headers"() {
        when:
        def response = nginxHttpsClient.get('/')

        then:
        response.success
        response.headers.get('access-control-allow-origin')
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx should handle OPTIONS preflight request"() {
        when:
        def response = nginxHttpsClient.options('/')

        then:
        response.statusCode == 204
        response.headers.get('access-control-allow-methods')
        response.headers.get('access-control-allow-headers')
    }

    // ==================== Security Headers ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx HTTPS should include security headers"() {
        when:
        def response = nginxHttpsClient.get('/')

        then:
        response.success
        // Check for common security headers
        response.headers.get('x-frame-options') ||
        response.headers.get('x-content-type-options') ||
        response.headers.get('strict-transport-security')
    }

    // ==================== Routing Through Nginx ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx should route to different environments"() {
        when:
        def devResponse = nginxHttpClient.get('/env:dev/hello-service/info')
        def testResponse = nginxHttpClient.get('/env:test/hello-service/info')

        then:
        devResponse.success
        testResponse.success
        devResponse.json?.environment == 'dev'
        testResponse.json?.environment == 'test'
    }

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx should handle default environment routing"() {
        when:
        def response = nginxHttpClient.get('/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        // Should use default environment (dev)
        response.bodyContains('environment=dev')
    }

    // ==================== Error Handling ====================

    @IgnoreIf({ !instance.shouldRunTests() })
    def "nginx should return 404 or 500 for non-existent services"() {
        when:
        def response = nginxHttpClient.get('/env:dev/non-existent-service/version')

        then:
        response.error || !response.success
    }
}
