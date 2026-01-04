package org.jadetipi.zuulconsul.discovery

import com.netflix.zuul.discovery.DiscoveryResult
import com.netflix.zuul.resolver.ResolverListener
import io.vertx.ext.consul.CheckStatus
import org.jadetipi.zuulconsul.consul.ConsulService
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Tests for ConsulServerResolver round-robin and RTT-based load balancing.
 */
class ConsulServerResolverSpec extends Specification {

    ConsulServiceRegistry registry = Mock()
    @Subject
    ConsulServerResolver resolver

    def setup() {
        registry.addServiceChangeListener("test-service", _ as ConsulServiceRegistry.ServiceChangeListener) >> { }
        registry.removeServiceChangeListener("test-service", _ as ConsulServiceRegistry.ServiceChangeListener) >> { }
    }

    // ==================== Basic Round-Robin Tests ====================

    def "should return EMPTY when no instances available"() {
        given:
        resolver = newResolver()
        registry.getServices(*_) >> []

        when:
        def result = resolver.resolve(null)

        then:
        result == DiscoveryResult.EMPTY
    }

    def "should return single instance when only one available"() {
        given:
        def service = createService("instance-1", "10.0.0.1", 8080, true)
        resolver = newResolver()
        registry.getServices(*_) >> [service]
        registry.getEstimatedRtt(*_) >> -1  // No coordinates

        when:
        def results = (1..5).collect { resolver.resolve(null) }

        then:
        results.every { it != null && it.getHost() == "10.0.0.1" && it.getPort() == 8080 }
    }

    def "should round-robin across multiple healthy instances"() {
        given:
        def services = [
            createService("instance-1", "10.0.0.1", 8080, true),
            createService("instance-2", "10.0.0.2", 8080, true),
            createService("instance-3", "10.0.0.3", 8080, true)
        ]
        resolver = newResolver()
        registry.getServices(*_) >> services
        registry.getEstimatedRtt(*_) >> -1  // No coordinates

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "each instance is selected twice in round-robin order"
        hosts.count("10.0.0.1") == 2
        hosts.count("10.0.0.2") == 2
        hosts.count("10.0.0.3") == 2
    }

    // ==================== Health-Based Selection Tests ====================

    def "should prefer healthy instances over unhealthy"() {
        given:
        def healthyService = createService("healthy-1", "10.0.0.1", 8080, true)
        def unhealthyService = createService("unhealthy-1", "10.0.0.2", 8080, false)
        resolver = newResolver()
        registry.getServices(*_) >> [healthyService, unhealthyService]
        registry.getEstimatedRtt(*_) >> -1

        when:
        def results = (1..5).collect { resolver.resolve(null) }

        then: "only healthy instance is selected"
        results.every { it.getHost() == "10.0.0.1" }
    }

    def "should fall back to unhealthy when no healthy instances"() {
        given:
        def services = [
            createService("unhealthy-1", "10.0.0.1", 8080, false),
            createService("unhealthy-2", "10.0.0.2", 8080, false)
        ]
        resolver = newResolver()
        registry.getServices(*_) >> services
        registry.getEstimatedRtt(*_) >> -1

        when:
        def results = (1..4).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "round-robins across unhealthy instances"
        hosts.count("10.0.0.1") == 2
        hosts.count("10.0.0.2") == 2
    }

    def "should round-robin across multiple healthy, ignoring unhealthy"() {
        given:
        def services = [
            createService("healthy-1", "10.0.0.1", 8080, true),
            createService("healthy-2", "10.0.0.2", 8080, true),
            createService("unhealthy-1", "10.0.0.3", 8080, false)
        ]
        resolver = newResolver()
        registry.getServices(*_) >> services
        registry.getEstimatedRtt(*_) >> -1

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "only healthy instances selected"
        hosts.count("10.0.0.1") == 3
        hosts.count("10.0.0.2") == 3
        hosts.count("10.0.0.3") == 0
    }

    // ==================== RTT-Based Selection Tests ====================

    def "should round-robin across equidistant instances (within threshold)"() {
        given: "three instances, two within 5ms threshold, one far"
        def closeService1 = createService("close-1", "10.0.0.1", 8080, true)
        def closeService2 = createService("close-2", "10.0.0.2", 8080, true)
        def farService = createService("far-1", "10.0.0.3", 8080, true)

        resolver = newResolver()
        registry.getServices(*_) >> [closeService1, closeService2, farService]
        registry.getEstimatedRtt(*_) >> { args ->
            def svc = args[0] as ConsulService
            switch(svc.getId()) {
                case "close-1": return 2.0f    // 2ms
                case "close-2": return 4.0f    // 4ms (within 5ms threshold of 2ms)
                case "far-1": return 50.0f     // 50ms (outside threshold)
                default: return -1f
            }
        }

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "only close instances selected (within threshold)"
        hosts.count("10.0.0.1") == 3
        hosts.count("10.0.0.2") == 3
        hosts.count("10.0.0.3") == 0
    }

    def "should include all instances when all within threshold"() {
        given: "all instances within 5ms threshold"
        def services = [
            createService("instance-1", "10.0.0.1", 8080, true),
            createService("instance-2", "10.0.0.2", 8080, true),
            createService("instance-3", "10.0.0.3", 8080, true)
        ]
        resolver = newResolver()
        registry.getServices(*_) >> services
        registry.getEstimatedRtt(*_) >> { args ->
            def svc = args[0] as ConsulService
            switch(svc.getId()) {
                case "instance-1": return 1.0f
                case "instance-2": return 3.0f
                case "instance-3": return 5.0f  // 5ms threshold from min (1ms)
                default: return -1f
            }
        }

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "all instances selected"
        hosts.count("10.0.0.1") == 2
        hosts.count("10.0.0.2") == 2
        hosts.count("10.0.0.3") == 2
    }

    def "should fall back to pure round-robin when no coordinates available"() {
        given: "no coordinates for any instance"
        def services = [
            createService("instance-1", "10.0.0.1", 8080, true),
            createService("instance-2", "10.0.0.2", 8080, true),
            createService("instance-3", "10.0.0.3", 8080, true)
        ]
        resolver = newResolver()
        registry.getServices(*_) >> services
        registry.getEstimatedRtt(_) >> -1  // No coordinates for any

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "all instances selected via round-robin"
        hosts.count("10.0.0.1") == 2
        hosts.count("10.0.0.2") == 2
        hosts.count("10.0.0.3") == 2
    }

    def "should include instances without coordinates in equidistant group"() {
        given: "some instances have coordinates, some don't"
        def serviceWithCoord = createService("with-coord", "10.0.0.1", 8080, true)
        def serviceNoCoord = createService("no-coord", "10.0.0.2", 8080, true)

        resolver = newResolver()
        registry.getServices(*_) >> [serviceWithCoord, serviceNoCoord]
        registry.getEstimatedRtt(_) >> { ConsulService svc ->
            svc.getId() == "with-coord" ? 2.0f : -1f
        }

        when:
        def results = (1..4).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "both instances selected (no-coord included in group)"
        hosts.count("10.0.0.1") == 2
        hosts.count("10.0.0.2") == 2
    }

    // ==================== Custom Threshold Tests ====================

    def "should respect custom RTT threshold"() {
        given: "resolver with 10ms threshold"
        resolver = new ConsulServerResolver(registry, "test-service", [], 10.0f)

        def closeService = createService("close", "10.0.0.1", 8080, true)
        def mediumService = createService("medium", "10.0.0.2", 8080, true)
        def farService = createService("far", "10.0.0.3", 8080, true)

        registry.getServices(*_) >> [closeService, mediumService, farService]
        registry.getEstimatedRtt(_) >> { ConsulService svc ->
            switch(svc.getId()) {
                case "close": return 5.0f     // 5ms
                case "medium": return 12.0f   // 12ms (within 10ms of 5ms = 15ms)
                case "far": return 50.0f      // 50ms (outside)
                default: return -1f
            }
        }

        when:
        def results = (1..6).collect { resolver.resolve(null) }
        def hosts = results.collect { it.getHost() }

        then: "close and medium selected (within 10ms threshold)"
        hosts.count("10.0.0.1") == 3
        hosts.count("10.0.0.2") == 3
        hosts.count("10.0.0.3") == 0
    }

    // ==================== Shutdown Tests ====================

    def "should return EMPTY after shutdown"() {
        given:
        def service = createService("instance-1", "10.0.0.1", 8080, true)
        resolver = new ConsulServerResolver(registry, "test-service", [])
        registry.getServices(*_) >> [service]

        when:
        resolver.shutdown()
        def result = resolver.resolve(null)

        then:
        result == DiscoveryResult.EMPTY
    }

    def "hasServers should return false after shutdown"() {
        given:
        resolver = new ConsulServerResolver(registry, "test-service", [])
        registry.getService("test-service", []) >> Optional.of(createService("instance-1", "10.0.0.1", 8080, true))

        when:
        resolver.shutdown()

        then:
        !resolver.hasServers()
    }

    // ==================== Tag Filtering Tests ====================

    def "should pass tags to registry"() {
        given:
        def tags = ["env:prod", "version:v2"]
        resolver = new ConsulServerResolver(registry, "test-service", tags)
        def service = createService("instance-1", "10.0.0.1", 8080, true)

        and: "stub the registry methods"
        registry.getServices("test-service", tags) >> [service]
        registry.getEstimatedRtt(_) >> -1

        when:
        def result = resolver.resolve(null)

        then:
        result.getHost() == "10.0.0.1"
    }

    def "should notify listener when instances are removed"() {
        given:
        def service1 = createService("instance-1", "10.0.0.1", 8080, true)
        def service2 = createService("instance-2", "10.0.0.2", 8080, true)
        registry.getServices("test-service", []) >>> [[], [service1, service2], [service1]]
        registry.addServiceChangeListener("test-service", _ as ConsulServiceRegistry.ServiceChangeListener) >> { }
        registry.removeServiceChangeListener("test-service", _ as ConsulServiceRegistry.ServiceChangeListener) >> { }
        def localResolver = new ConsulServerResolver(registry, "test-service", [])
        def listener = Mock(ResolverListener)
        localResolver.setListener(listener)
        registry.getEstimatedRtt(*_) >> -1

        when:
        localResolver.resolve(null)
        localResolver.onServiceInstancesChanged("test-service")

        then:
        1 * listener.onChange({ removed ->
            removed.size() == 1 && removed.first().host == "10.0.0.2"
        })
    }

    // ==================== Helper Methods ====================

    private ConsulService createService(String id, String address, int port, boolean healthy) {
        def service = Mock(ConsulService) {
            getId() >> id
            getName() >> "test-service"
            getAddress() >> address
            getPort() >> port
            isHealthy() >> healthy
            getHealthStatus() >> (healthy ? CheckStatus.PASSING : CheckStatus.CRITICAL)
            getNodeName() >> "node-${id}"
            getUri() >> URI.create("http://${address}:${port}")
        }
        return service
    }

    private ConsulServerResolver newResolver(List<String> tags = []) {
        new ConsulServerResolver(registry, "test-service", tags)
    }
}
