package org.jadetipi.zuulconsul.consul

import io.vertx.ext.consul.CheckStatus
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for ConsulServiceCache service caching and health-based selection.
 */
class ConsulServiceCacheSpec extends Specification {

    @Subject
    ConsulServiceCache cache

    def setup() {
        cache = new ConsulServiceCache(["dev", "prod"] as Set)
    }

    // ==================== Basic Cache Operations ====================

    def "should store and retrieve services"() {
        given:
        def services = [
            createService("instance-1", "10.0.0.1", true),
            createService("instance-2", "10.0.0.2", true)
        ]

        when:
        cache.updateServices("my-service", services)

        then:
        cache.getServices("my-service").size() == 2
        cache.getServiceNames() == ["my-service"] as Set
    }

    def "should return empty list for unknown service"() {
        expect:
        cache.getServices("unknown-service").isEmpty()
    }

    def "should remove service from cache"() {
        given:
        cache.updateServices("my-service", [createService("instance-1", "10.0.0.1", true)])

        when:
        cache.removeService("my-service")

        then:
        cache.getServices("my-service").isEmpty()
        !cache.getServiceNames().contains("my-service")
    }

    def "should clear all services"() {
        given:
        cache.updateServices("service-1", [createService("instance-1", "10.0.0.1", true)])
        cache.updateServices("service-2", [createService("instance-2", "10.0.0.2", true)])

        when:
        cache.clear()

        then:
        cache.getServiceNames().isEmpty()
        cache.getTotalInstanceCount() == 0
    }

    // ==================== Health-Based Selection ====================

    def "getService should return healthy instance when available"() {
        given:
        def healthyService = createService("healthy", "10.0.0.1", true)
        def unhealthyService = createService("unhealthy", "10.0.0.2", false)
        cache.updateServices("my-service", [unhealthyService, healthyService])

        when:
        def result = cache.getService("my-service", [])

        then:
        result.isPresent()
        result.get().getId() == "healthy"
    }

    def "getService should fall back to unhealthy when no healthy instances"() {
        given:
        def unhealthyService = createService("unhealthy", "10.0.0.1", false)
        cache.updateServices("my-service", [unhealthyService])

        when:
        def result = cache.getService("my-service", [])

        then:
        result.isPresent()
        result.get().getId() == "unhealthy"
    }

    def "getService should return empty for unknown service"() {
        expect:
        !cache.getService("unknown", []).isPresent()
    }

    def "getService should return first healthy instance (maintains order)"() {
        given:
        def healthy1 = createService("healthy-1", "10.0.0.1", true)
        def healthy2 = createService("healthy-2", "10.0.0.2", true)
        cache.updateServices("my-service", [healthy1, healthy2])

        when:
        def result = cache.getService("my-service", [])

        then:
        result.isPresent()
        result.get().getId() == "healthy-1"
    }

    // ==================== Tag Filtering ====================

    def "getServices should filter by tags"() {
        given:
        def devService = createServiceWithTags("dev-instance", "10.0.0.1", true, ["env:dev"])
        def prodService = createServiceWithTags("prod-instance", "10.0.0.2", true, ["env:prod"])
        cache.updateServices("my-service", [devService, prodService])

        when:
        def devResults = cache.getServices("my-service", ["env:dev"])
        def prodResults = cache.getServices("my-service", ["env:prod"])

        then:
        devResults.size() == 1
        devResults[0].getId() == "dev-instance"
        prodResults.size() == 1
        prodResults[0].getId() == "prod-instance"
    }

    def "getServices should return all when no tags specified"() {
        given:
        def service1 = createServiceWithTags("instance-1", "10.0.0.1", true, ["env:dev"])
        def service2 = createServiceWithTags("instance-2", "10.0.0.2", true, ["env:prod"])
        cache.updateServices("my-service", [service1, service2])

        when:
        def results = cache.getServices("my-service", [])

        then:
        results.size() == 2
    }

    def "getServices should match multiple tags"() {
        given:
        def matchingService = createServiceWithTags("match", "10.0.0.1", true, ["env:dev", "version:v1"])
        def partialService = createServiceWithTags("partial", "10.0.0.2", true, ["env:dev"])
        cache.updateServices("my-service", [matchingService, partialService])

        when:
        def results = cache.getServices("my-service", ["env:dev", "version:v1"])

        then:
        results.size() == 1
        results[0].getId() == "match"
    }

    // ==================== Environment Reachability ====================

    def "should allow reachable environments"() {
        expect:
        cache.isEnvironmentReachable("dev")
        cache.isEnvironmentReachable("prod")
    }

    def "should reject unreachable environments"() {
        expect:
        !cache.isEnvironmentReachable("staging")
        !cache.isEnvironmentReachable("unknown")
    }

    def "should allow all environments when no restrictions"() {
        given:
        def unrestrictedCache = new ConsulServiceCache([] as Set)

        expect:
        unrestrictedCache.isEnvironmentReachable("any-env")
        unrestrictedCache.isEnvironmentReachable("dev")
        unrestrictedCache.isEnvironmentReachable("prod")
    }

    // ==================== Instance Counting ====================

    def "should count total instances across services"() {
        given:
        cache.updateServices("service-1", [
            createService("s1-i1", "10.0.0.1", true),
            createService("s1-i2", "10.0.0.2", true)
        ])
        cache.updateServices("service-2", [
            createService("s2-i1", "10.0.0.3", true)
        ])

        expect:
        cache.getTotalInstanceCount() == 3
    }

    // ==================== Helper Methods ====================

    private ConsulService createService(String id, String address, boolean healthy) {
        return createServiceWithTags(id, address, healthy, [])
    }

    private ConsulService createServiceWithTags(String id, String address, boolean healthy, List<String> serviceTags) {
        def service = Mock(ConsulService) {
            getId() >> id
            getAddress() >> address
            getPort() >> 8080
            isHealthy() >> healthy
            getHealthStatus() >> (healthy ? CheckStatus.PASSING : CheckStatus.CRITICAL)
            getTags() >> serviceTags
            matchesTags(_) >> { args ->
                List<String> requiredTags = args[0] as List<String>
                if (requiredTags == null || requiredTags.isEmpty()) {
                    return true
                }
                return serviceTags.containsAll(requiredTags)
            }
            getUri() >> URI.create("http://${address}:8080")
        }
        return service
    }
}
