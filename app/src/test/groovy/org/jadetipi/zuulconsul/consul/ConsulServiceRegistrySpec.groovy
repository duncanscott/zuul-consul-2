package org.jadetipi.zuulconsul.consul

import io.vertx.core.Vertx
import spock.lang.Specification

class ConsulServiceRegistrySpec extends Specification {

    Vertx vertx

    def setup() {
        vertx = Vertx.vertx()
    }

    def cleanup() {
        vertx?.close()?.toCompletionStage()?.toCompletableFuture()?.join()
    }

    private ConsulServiceRegistry newRegistry(String defaultEnv, List<String> tags = [], Set<String> reachable = [] as Set) {
        new ConsulServiceRegistry(
            vertx,
            "localhost",
            8500,
            null,
            null,
            defaultEnv,
            reachable,
            tags)
    }

    def "default environment tag is added when not provided"() {
        given:
        def registry = newRegistry("dev")

        expect:
        registry.getDefaultTags().contains("env:dev")

        cleanup:
        registry.close()
    }

    def "default environment overrides env tags from defaults"() {
        given:
        def registry = newRegistry("dev", ["env:prod", "version:v1"])

        expect:
        registry.getDefaultTags().containsAll(["version:v1", "env:dev"])
        !registry.getDefaultTags().contains("env:prod")

        cleanup:
        registry.close()
    }

    def "default environment added to reachable environments"() {
        given:
        def registry = newRegistry("dev", [], ["prod"] as Set)

        expect:
        registry.getReachableEnvironments().containsAll(["prod", "dev"])

        cleanup:
        registry.close()
    }
}
