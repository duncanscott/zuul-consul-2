package org.jadetipi.zuulconsul.server;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.config.ConfigurationManager;
import com.netflix.netty.common.accesslog.AccessLogPublisher;
import com.netflix.netty.common.metrics.EventLoopGroupMetrics;
import com.netflix.netty.common.status.ServerStatusManager;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.zuul.BasicFilterUsageNotifier;
import com.netflix.zuul.BasicRequestCompleteHandler;
import com.netflix.zuul.DefaultFilterFactory;
import com.netflix.zuul.FilterFactory;
import com.netflix.zuul.StaticFilterLoader;
import com.netflix.zuul.context.ZuulSessionContextDecorator;
import com.netflix.zuul.filters.ZuulFilter;
import com.netflix.zuul.netty.server.ClientRequestReceiver;
import com.netflix.zuul.netty.server.DirectMemoryMonitor;
import com.netflix.zuul.netty.server.Server;
import io.vertx.core.Vertx;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;
import org.jadetipi.zuulconsul.endpoints.ServiceRegistryEndpoint;
import org.jadetipi.zuulconsul.filters.ContextRootFilter;
import org.jadetipi.zuulconsul.origins.ConsulOriginManager;
import org.jadetipi.zuulconsul.security.JwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import outbound.StatsFilter;
import inbound.ConsulRoutingFilter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Zuul Consul gateway server.
 * <p>
 * This class bootstraps the Zuul 2 server with Consul-based service discovery.
 * It initializes:
 * <ul>
 *   <li>Vert.x for async I/O</li>
 *   <li>Consul service registry for service discovery</li>
 *   <li>Zuul filters for request routing</li>
 *   <li>Netty server for HTTP handling</li>
 * </ul>
 */
public class ZuulConsulServer {

    private static final Logger log = LoggerFactory.getLogger(ZuulConsulServer.class);

    private static final Set<Class<? extends ZuulFilter<?, ?>>> FILTER_TYPES;

    static {
        Set<Class<? extends ZuulFilter<?, ?>>> classes = new LinkedHashSet<>();
        classes.add(ConsulRoutingFilter.class);
        classes.add(ContextRootFilter.class);
        classes.add(ServiceRegistryEndpoint.class);
        classes.add(StatsFilter.class);
        FILTER_TYPES = Collections.unmodifiableSet(classes);
    }

    private final Vertx vertx;
    private final ConsulServiceRegistry consulRegistry;
    private final ConsulOriginManager originManager;
    private final Registry metricsRegistry;
    private Server server;

    public ZuulConsulServer() {
        this.vertx = Vertx.vertx();
        this.consulRegistry = ConsulServiceRegistry.fromEnvironment(vertx);
        this.metricsRegistry = new DefaultRegistry();
        this.originManager = new ConsulOriginManager(metricsRegistry, consulRegistry);
    }

    public void start() {
        long startNanos = System.nanoTime();
        log.info("Zuul Consul: starting up...");

        try {
            // Load configuration
            ConfigurationManager.loadCascadedPropertiesFromResources("application");

            // Start Consul watches for real-time service updates (with fallback refresh)
            consulRegistry.startWatching();

            // Create access log publisher
            AccessLogPublisher accessLogPublisher = new AccessLogPublisher(
                "ACCESS",
                (channel, httpRequest) -> ClientRequestReceiver.getRequestFromChannel(channel)
                    .getContext()
                    .getUUID());

            // Create application info manager (no Eureka registration)
            ApplicationInfoManager appInfoManager = new ApplicationInfoManager(null, null, null);

            // Create JWT validator for securing the service registry endpoint
            JwtValidator jwtValidator = new JwtValidator();

            // Create filter factory
            FilterFactory filterFactory = new ConsulFilterFactory(consulRegistry, jwtValidator, metricsRegistry);

            // Create server startup
            ConsulServerStartup serverStartup = new ConsulServerStartup(
                new ServerStatusManager(appInfoManager) {
                    @Override
                    public void localStatus(InstanceStatus status) {
                        log.info("Server status: {}", status);
                    }
                },
                new StaticFilterLoader(filterFactory, FILTER_TYPES),
                new ZuulSessionContextDecorator(originManager),
                new BasicFilterUsageNotifier(metricsRegistry),
                new BasicRequestCompleteHandler(),
                metricsRegistry,
                new DirectMemoryMonitor(metricsRegistry),
                new EventLoopGroupMetrics(metricsRegistry),
                null, // No Eureka client
                appInfoManager,
                accessLogPublisher);

            serverStartup.init();
            server = serverStartup.server();
            server.start();

            long startupDuration = System.nanoTime() - startNanos;
            log.info("Zuul Consul: finished startup. Duration = {}ms",
                TimeUnit.NANOSECONDS.toMillis(startupDuration));

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

            // Wait for termination
            server.awaitTermination();

        } catch (Throwable t) {
            log.error("Zuul Consul: initialization failed", t);
            t.printStackTrace();
            System.err.println("###############");
            System.err.println("Zuul Consul: initialization failed. Forcing shutdown now.");
            System.err.println("###############");
            stop();
            System.exit(1);
        }
    }

    public void stop() {
        log.info("Zuul Consul: shutting down...");

        try {
            if (server != null) {
                server.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping server", e);
        }

        try {
            originManager.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down origin manager", e);
        }

        try {
            consulRegistry.close();
        } catch (Exception e) {
            log.warn("Error closing Consul registry", e);
        }

        try {
            vertx.close();
        } catch (Exception e) {
            log.warn("Error closing Vert.x", e);
        }

        log.info("Zuul Consul: shutdown complete");
    }

    /**
     * Filter factory that injects dependencies into filters.
     */
    private static class ConsulFilterFactory implements FilterFactory {

        private final DefaultFilterFactory defaultFactory;
        private final ConsulServiceRegistry consulRegistry;
        private final JwtValidator jwtValidator;
        private final Registry metricsRegistry;

        public ConsulFilterFactory(
            ConsulServiceRegistry consulRegistry,
            JwtValidator jwtValidator,
            Registry metricsRegistry) {
            this.defaultFactory = new DefaultFilterFactory();
            this.consulRegistry = consulRegistry;
            this.jwtValidator = jwtValidator;
            this.metricsRegistry = metricsRegistry;
        }

        @Override
        public ZuulFilter<?, ?> newInstance(Class<?> clazz) throws Exception {
            // Inject dependencies into ServiceRegistryEndpoint
            if (clazz == ServiceRegistryEndpoint.class) {
                return new ServiceRegistryEndpoint(consulRegistry, jwtValidator);
            }
            if (clazz == ContextRootFilter.class) {
                return new ContextRootFilter(consulRegistry);
            }
            if (clazz == StatsFilter.class) {
                return new StatsFilter(metricsRegistry);
            }
            return defaultFactory.newInstance(clazz);
        }
    }

    public static void main(String[] args) {
        new ZuulConsulServer().start();
    }
}
