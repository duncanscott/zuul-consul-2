package org.jadetipi.zuulconsul.functional

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.IgnoreIf

/**
 * Functional tests for Consul service discovery.
 *
 * These tests verify that the Docker Compose environment
 * has properly registered services with Consul.
 */
class ConsulDiscoverySpec extends Specification {

    @Shared
    HttpTestClient consulClient

    @Shared
    boolean consulAvailable = false

    def setupSpec() {
        consulClient = new HttpTestClient(FunctionalTestConfig.CONSUL_URL)
        consulAvailable = consulClient.waitForAvailable('/v1/status/leader', 5)

        if (!consulAvailable) {
            println "WARNING: Consul is not available at ${FunctionalTestConfig.CONSUL_URL}"
            println "Start the Docker environment with: docker-compose -f docker/docker-compose.yml up -d"
        }
    }

    // ==================== Consul Availability ====================

    def "Consul should be available"() {
        expect:
        consulAvailable
    }

    // ==================== Service Registration ====================

    @IgnoreIf({ !instance.consulAvailable })
    def "hello-service should be registered in Consul"() {
        when:
        def response = consulClient.get('/v1/catalog/service/hello-service')

        then:
        response.success
        def services = response.json as List
        services.size() >= 1
    }

    @IgnoreIf({ !instance.consulAvailable })
    def "hello-service should have dev and test instances"() {
        when:
        def response = consulClient.get('/v1/catalog/service/hello-service')

        then:
        response.success
        def services = response.json as List

        and: "should have at least 2 instances"
        services.size() >= 2

        and: "should have dev environment instance"
        services.any { it.ServiceTags?.contains('env:dev') }

        and: "should have test environment instance"
        services.any { it.ServiceTags?.contains('env:test') }
    }

    @IgnoreIf({ !instance.consulAvailable })
    def "echo-service should be registered in Consul"() {
        when:
        def response = consulClient.get('/v1/catalog/service/echo-service')

        then:
        response.success
        def services = response.json as List
        services.size() >= 1
    }

    // ==================== Health Checks ====================

    @IgnoreIf({ !instance.consulAvailable })
    def "services should be healthy"() {
        when:
        def response = consulClient.get('/v1/health/state/passing')

        then:
        response.success
        def checks = response.json as List
        // Should have passing health checks for our services
        checks.size() >= 1
    }

    @IgnoreIf({ !instance.consulAvailable })
    def "hello-service instances should have passing health checks"() {
        when:
        def response = consulClient.get('/v1/health/service/hello-service?passing=true')

        then:
        response.success
        def services = response.json as List
        services.size() >= 2 // dev and test instances
    }

    // ==================== Service Catalog ====================

    @IgnoreIf({ !instance.consulAvailable })
    def "Consul catalog should list registered services"() {
        when:
        def response = consulClient.get('/v1/catalog/services')

        then:
        response.success
        def services = response.json as Map
        services.containsKey('hello-service')
        services.containsKey('echo-service')
    }
}
