package org.jadetipi.zuulconsul.filters

import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.message.http.HttpRequestMessage
import org.jadetipi.zuulconsul.consul.ConsulService
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry
import spock.lang.Specification

import java.net.URI
import java.util.Optional

class ContextRootFilterSpec extends Specification {

    def registry = Mock(ConsulServiceRegistry)
    def filter = new ContextRootFilter(registry)

    def "prepends context root and records backend uri"() {
        given:
        def ctx = new SessionContext()
        ctx.set("consul.serviceName", "svc")
        ctx.set("consul.serviceTags", ["env:dev"])
        def request = Mock(HttpRequestMessage)
        request.getContext() >> ctx
        request.getPath() >> "/users"
        request.getQueryParams() >> null

        def service = Stub(ConsulService) {
            getContextRoot() >> "/api"
            getUri() >> new URI("http://example:8080/api")
        }
        registry.getService("svc", ["env:dev"]) >> Optional.of(service)

        when:
        filter.apply(request)

        then:
        1 * request.setPath("/api/users")
        ctx.get("consul.requestPath") == "/api/users"
        ctx.get("consul.serviceUri") == "http://example:8080/api/users"
    }
}
