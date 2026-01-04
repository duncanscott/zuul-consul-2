package outbound

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.Timer
import com.netflix.zuul.context.SessionContext
import com.netflix.zuul.message.http.HttpRequestMessage
import com.netflix.zuul.message.http.HttpResponseMessage
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class StatsFilterSpec extends Specification {

    def registry = Mock(Registry)
    def timer = Mock(Timer)

    def "records metrics when start time is available"() {
        given:
        def filter = new StatsFilter(registry)
        def ctx = new SessionContext()
        ctx.set(StatsFilter.CONSUL_SERVICE_NAME, "svc-demo")
        ctx.set(StatsFilter.CONSUL_REQUEST_PATH, "/api")
        ctx.set(StatsFilter.CONSUL_START_NANO, System.nanoTime() - 5_000_000L) // ~5ms

        def request = Mock(HttpRequestMessage) {
            getMethod() >> "GET"
            getPath() >> "/api"
        }
        def response = Mock(HttpResponseMessage) {
            getContext() >> ctx
            getInboundRequest() >> request
            getStatus() >> 200
        }

        registry.timer(*_) >> timer

        when:
        filter.apply(response)

        then:
        1 * timer.record(_, TimeUnit.MILLISECONDS)
    }
}
