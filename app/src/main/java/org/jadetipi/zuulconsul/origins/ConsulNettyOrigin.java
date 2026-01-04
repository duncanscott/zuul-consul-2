package org.jadetipi.zuulconsul.origins;

import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.config.CachedDynamicBooleanProperty;
import com.netflix.config.CachedDynamicIntProperty;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Registry;
import com.netflix.zuul.context.CommonContextKeys;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.discovery.DiscoveryResult;
import com.netflix.zuul.exception.ErrorType;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.netty.NettyRequestAttemptFactory;
import com.netflix.zuul.netty.SpectatorUtils;
import com.netflix.zuul.netty.connectionpool.ClientChannelManager;
import com.netflix.zuul.netty.connectionpool.DefaultClientChannelManager;
import com.netflix.zuul.netty.connectionpool.PooledConnection;
import com.netflix.zuul.niws.RequestAttempt;
import com.netflix.zuul.origins.NettyOrigin;
import com.netflix.zuul.origins.OriginConcurrencyExceededException;
import com.netflix.zuul.origins.OriginName;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.stats.status.StatusCategory;
import com.netflix.zuul.stats.status.StatusCategoryUtils;
import com.netflix.zuul.stats.status.ZuulStatusCategory;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Promise;
import org.jadetipi.zuulconsul.consul.ConsulServiceRegistry;
import org.jadetipi.zuulconsul.discovery.ConsulServerResolver;
import org.jadetipi.zuulconsul.server.EnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty origin that uses Consul for service discovery.
 * <p>
 * This origin integrates with Zuul 2's connection pool infrastructure
 * while using our Consul-based service discovery instead of Eureka/Ribbon.
 */
public class ConsulNettyOrigin implements NettyOrigin {

    private static final Logger log = LoggerFactory.getLogger(ConsulNettyOrigin.class);

    private final OriginName originName;
    private final Registry registry;
    private final IClientConfig config;
    private final ClientChannelManager clientChannelManager;
    private final ConsulServerResolver resolver;
    private final NettyRequestAttemptFactory requestAttemptFactory;

    private final AtomicInteger concurrentRequests;
    private final Counter rejectedRequests;
    private final CachedDynamicIntProperty concurrencyMax;
    private final CachedDynamicBooleanProperty concurrencyProtectionEnabled;

    /**
     * Create a Consul-based Netty origin.
     *
     * @param originName the origin name (typically the service name)
     * @param registry   Spectator metrics registry
     * @param consulRegistry the Consul service registry
     * @param tags       tags to filter services by
     */
    public ConsulNettyOrigin(
            OriginName originName,
            Registry registry,
            ConsulServiceRegistry consulRegistry,
            List<String> tags) {

        this.originName = Objects.requireNonNull(originName, "originName");
        this.registry = registry;
        this.config = setupClientConfig(originName);
        this.resolver = new ConsulServerResolver(consulRegistry, originName.getTarget(), tags);
        this.clientChannelManager = new DefaultClientChannelManager(originName, config, resolver, registry);
        this.clientChannelManager.init();
        this.requestAttemptFactory = new NettyRequestAttemptFactory();

        String niwsClientName = getName().getNiwsClientName();
        this.concurrentRequests =
            SpectatorUtils.newGauge("zuul.origin.concurrent.requests", niwsClientName, new AtomicInteger(0));
        this.rejectedRequests = SpectatorUtils.newCounter("zuul.origin.rejected.requests", niwsClientName);
        this.concurrencyMax =
            new CachedDynamicIntProperty("zuul.origin." + niwsClientName + ".concurrency.max.requests", 200);
        this.concurrencyProtectionEnabled = new CachedDynamicBooleanProperty(
            "zuul.origin." + niwsClientName + ".concurrency.protect.enabled", true);

        log.info("Created ConsulNettyOrigin for service: {}, tags: {}", originName.getTarget(), tags);
    }

    protected IClientConfig setupClientConfig(OriginName originName) {
        IClientConfig niwsClientConfig =
            DefaultClientConfigImpl.getClientConfigWithDefaultValues(originName.getNiwsClientName());
        niwsClientConfig.set(CommonClientConfigKey.ClientClassName, originName.getNiwsClientName());
        niwsClientConfig.loadProperties(originName.getNiwsClientName());

        // Apply configuration from environment variables / application.properties
        // This ensures our config takes precedence over Ribbon's default property resolution
        niwsClientConfig.set(CommonClientConfigKey.ConnectTimeout, EnvironmentConfig.getConnectTimeout());
        niwsClientConfig.set(CommonClientConfigKey.ReadTimeout, EnvironmentConfig.getReadTimeout());
        niwsClientConfig.set(CommonClientConfigKey.MaxAutoRetries, EnvironmentConfig.getMaxAutoRetries());
        niwsClientConfig.set(CommonClientConfigKey.MaxAutoRetriesNextServer, EnvironmentConfig.getMaxAutoRetriesNextServer());

        log.debug("Client config for {}: connectTimeout={}, readTimeout={}, maxRetries={}, maxRetriesNextServer={}",
            originName.getTarget(),
            niwsClientConfig.get(CommonClientConfigKey.ConnectTimeout),
            niwsClientConfig.get(CommonClientConfigKey.ReadTimeout),
            niwsClientConfig.get(CommonClientConfigKey.MaxAutoRetries),
            niwsClientConfig.get(CommonClientConfigKey.MaxAutoRetriesNextServer));

        return niwsClientConfig;
    }

    @Override
    public OriginName getName() {
        return originName;
    }

    @Override
    public boolean isAvailable() {
        return clientChannelManager.isAvailable();
    }

    @Override
    public boolean isCold() {
        return clientChannelManager.isCold();
    }

    @Override
    public Promise<PooledConnection> connectToOrigin(
            HttpRequestMessage zuulReq,
            EventLoop eventLoop,
            int attemptNumber,
            CurrentPassport passport,
            AtomicReference<DiscoveryResult> chosenServer,
            AtomicReference<? super InetAddress> chosenHostAddr) {
        return clientChannelManager.acquire(eventLoop, null, passport, chosenServer, chosenHostAddr);
    }

    @Override
    public int getMaxRetriesForRequest(SessionContext context) {
        return config.get(CommonClientConfigKey.MaxAutoRetriesNextServer, 0);
    }

    @Override
    public RequestAttempt newRequestAttempt(
            DiscoveryResult server, InetAddress serverAddr, SessionContext zuulCtx, int attemptNum) {
        return new RequestAttempt(
            server, serverAddr, config, attemptNum, config.get(CommonClientConfigKey.ReadTimeout));
    }

    @Override
    public String getIpAddrFromServer(DiscoveryResult discoveryResult) {
        Optional<String> ipAddr = discoveryResult.getIPAddr();
        return ipAddr.orElse(null);
    }

    @Override
    public IClientConfig getClientConfig() {
        return config;
    }

    @Override
    public Registry getSpectatorRegistry() {
        return registry;
    }

    @Override
    public void recordFinalError(HttpRequestMessage requestMsg, Throwable throwable) {
        if (throwable == null) {
            return;
        }

        SessionContext zuulCtx = requestMsg.getContext();

        ErrorType et = requestAttemptFactory.mapNettyToOutboundErrorType(throwable);
        StatusCategory nfs = et.getStatusCategory();
        StatusCategoryUtils.setStatusCategory(zuulCtx, nfs);
        StatusCategoryUtils.setOriginStatusCategory(zuulCtx, nfs);

        zuulCtx.setError(throwable);
    }

    @Override
    public void recordFinalResponse(HttpResponseMessage resp) {
        if (resp != null) {
            SessionContext zuulCtx = resp.getContext();

            int originStatusCode = resp.getStatus();
            zuulCtx.put(CommonContextKeys.ORIGIN_STATUS, originStatusCode);

            StatusCategory originNfs = ZuulStatusCategory.SUCCESS;
            if (originStatusCode == 503) {
                originNfs = ZuulStatusCategory.FAILURE_ORIGIN_THROTTLED;
            } else if (StatusCategoryUtils.isResponseHttpErrorStatus(originStatusCode)) {
                originNfs = ZuulStatusCategory.FAILURE_ORIGIN;
            }
            StatusCategoryUtils.setOriginStatusCategory(zuulCtx, originNfs);
            StatusCategoryUtils.storeStatusCategoryIfNotAlreadyFailure(zuulCtx, originNfs);
        }
    }

    @Override
    public void preRequestChecks(HttpRequestMessage zuulRequest) {
        if (concurrencyProtectionEnabled.get() && concurrentRequests.get() > concurrencyMax.get()) {
            rejectedRequests.increment();
            throw new OriginConcurrencyExceededException(getName());
        }

        concurrentRequests.incrementAndGet();
    }

    @Override
    public void recordProxyRequestEnd() {
        concurrentRequests.decrementAndGet();
    }

    @Override
    public double getErrorPercentage() {
        return 0;
    }

    @Override
    public double getErrorAllPercentage() {
        return 0;
    }

    @Override
    public void onRequestExecutionStart(HttpRequestMessage zuulReq) {}

    @Override
    public void onRequestStartWithServer(HttpRequestMessage zuulReq, DiscoveryResult discoveryResult, int attemptNum) {}

    @Override
    public void onRequestExceptionWithServer(
            HttpRequestMessage zuulReq, DiscoveryResult discoveryResult, int attemptNum, Throwable t) {}

    @Override
    public void onRequestExecutionSuccess(
            HttpRequestMessage zuulReq,
            HttpResponseMessage zuulResp,
            DiscoveryResult discoveryResult,
            int attemptNum) {}

    @Override
    public void onRequestExecutionFailed(
            HttpRequestMessage zuulReq, DiscoveryResult discoveryResult, int attemptNum, Throwable t) {}

    @Override
    public void adjustRetryPolicyIfNeeded(HttpRequestMessage zuulRequest) {}

    @Override
    public void recordSuccessResponse() {}

    /**
     * Get the underlying Consul server resolver.
     */
    public ConsulServerResolver getResolver() {
        return resolver;
    }

    /**
     * Shutdown the origin and release resources.
     */
    public void shutdown() {
        clientChannelManager.shutdown();
        resolver.shutdown();
    }
}
