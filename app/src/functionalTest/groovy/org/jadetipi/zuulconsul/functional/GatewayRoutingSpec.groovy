package org.jadetipi.zuulconsul.functional

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.IgnoreIf

/**
 * Functional tests for Zuul Consul gateway routing.
 *
 * Prerequisites:
 * 1. Docker Compose environment running: docker-compose -f docker/docker-compose.yml up -d
 * 2. Zuul Consul gateway running locally with:
 *    - ZUUL_CONSUL_AGENT_HOST=localhost
 *    - ZUUL_CONSUL_AGENT_PORT=8500
 *    - ZUUL_DEFAULT_ENVIRONMENT=dev
 *    - ZUUL_REACHABLE_ENVIRONMENTS=dev:test
 *
 * Run with: ./gradlew :app:functionalTest
 */
@Stepwise
class GatewayRoutingSpec extends Specification {

    @Shared
    HttpTestClient consulClient

    @Shared
    HttpTestClient gatewayClient

    @Shared
    boolean environmentReady = false

    def setupSpec() {
        consulClient = new HttpTestClient(FunctionalTestConfig.CONSUL_URL)
        gatewayClient = new HttpTestClient(FunctionalTestConfig.ZUUL_URL)

        // Check if the test environment is available
        environmentReady = checkEnvironment()
    }

    private boolean checkEnvironment() {
        println "Checking test environment..."
        println "Consul URL: ${FunctionalTestConfig.CONSUL_URL}"
        println "Gateway URL: ${FunctionalTestConfig.ZUUL_URL}"

        // Check Consul
        if (!consulClient.waitForAvailable('/v1/status/leader', 5)) {
            println "WARNING: Consul is not available at ${FunctionalTestConfig.CONSUL_URL}"
            println "Start the Docker environment with: docker-compose -f docker/docker-compose.yml up -d"
            return false
        }
        println "Consul is available"

        // Check Gateway
        if (!gatewayClient.waitForAvailable('/', 5)) {
            println "WARNING: Gateway is not available at ${FunctionalTestConfig.ZUUL_URL}"
            println "Start the gateway with appropriate environment variables"
            return false
        }
        println "Gateway is available"

        return true
    }

    // ==================== Environment Checks ====================

    def "test environment should be available"() {
        expect:
        environmentReady
    }

    // ==================== Service Registry Endpoint ====================

    @IgnoreIf({ !instance.environmentReady })
    def "gateway root endpoint should return service registry"() {
        when:
        def response = gatewayClient.get('/')

        then:
        response.success
        response.bodyContains('service_cache')
        response.bodyContains('hello-service') || response.bodyContains('echo-service')
    }

    // ==================== Basic Routing ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should route to hello-service with dev environment tag"() {
        when:
        def response = gatewayClient.get('/env:dev/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        response.bodyContains('environment=dev')
    }

    @IgnoreIf({ !instance.environmentReady })
    def "should route to hello-service with test environment tag"() {
        when:
        def response = gatewayClient.get('/env:test/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        response.bodyContains('environment=test')
    }

    @IgnoreIf({ !instance.environmentReady })
    def "should route to echo-service"() {
        when:
        def response = gatewayClient.get('/env:dev/echo-service/version')

        then:
        response.success
        response.bodyContains('echo-service')
    }

    // ==================== Default Tag Routing ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should route using default environment when no tag specified"() {
        when:
        def response = gatewayClient.get('/hello-service/version')

        then:
        response.success
        response.bodyContains('hello-service')
        // Should use default environment (dev)
        response.bodyContains('environment=dev')
    }

    // ==================== Health Check Endpoints ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should proxy health endpoint"() {
        when:
        def response = gatewayClient.get('/env:dev/hello-service/health')

        then:
        response.success
        response.bodyContains('OK')
    }

    // ==================== JSON Response Handling ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should proxy JSON responses correctly"() {
        when:
        def response = gatewayClient.get('/env:dev/hello-service/info')

        then:
        response.success
        def json = response.json
        json.service == 'hello-service'
        json.environment == 'dev'
    }

    // ==================== Error Cases ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should return error for non-existent service"() {
        when:
        def response = gatewayClient.get('/env:dev/non-existent-service/version')

        then:
        response.error || !response.success
    }

    // ==================== Multiple Path Segments ====================

    @IgnoreIf({ !instance.environmentReady })
    def "should handle paths with multiple segments"() {
        when:
        def response = gatewayClient.get('/env:dev/echo-service/echo')

        then:
        response.success
        response.bodyContains('echo-service')
    }
}
