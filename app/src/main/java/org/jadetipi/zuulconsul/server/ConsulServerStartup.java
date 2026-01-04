package org.jadetipi.zuulconsul.server;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicStringProperty;
import com.netflix.discovery.EurekaClient;
import com.netflix.netty.common.accesslog.AccessLogPublisher;
import com.netflix.netty.common.channel.config.ChannelConfig;
import com.netflix.netty.common.channel.config.CommonChannelConfigKeys;
import com.netflix.netty.common.metrics.EventLoopGroupMetrics;
import com.netflix.netty.common.proxyprotocol.StripUntrustedProxyHeadersHandler;
import com.netflix.netty.common.ssl.ServerSslConfig;
import com.netflix.netty.common.status.ServerStatusManager;
import com.netflix.spectator.api.Registry;
import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.FilterUsageNotifier;
import com.netflix.zuul.RequestCompleteHandler;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.netty.server.BaseServerStartup;
import com.netflix.zuul.netty.server.DefaultEventLoopConfig;
import com.netflix.zuul.netty.server.DirectMemoryMonitor;
import com.netflix.zuul.netty.server.NamedSocketAddress;
import com.netflix.zuul.netty.server.SocketAddressProperty;
import com.netflix.zuul.netty.server.Http1MutualSslChannelInitializer;
import com.netflix.zuul.netty.server.ZuulServerChannelInitializer;
import com.netflix.zuul.netty.ssl.BaseSslContextFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server startup configuration for Zuul Consul gateway.
 * <p>
 * This class configures the Netty server for HTTP proxying with
 * Consul-based service discovery.
 */
public class ConsulServerStartup extends BaseServerStartup {

    private static final Logger log = LoggerFactory.getLogger(ConsulServerStartup.class);

    public ConsulServerStartup(
            ServerStatusManager serverStatusManager,
            FilterLoader filterLoader,
            SessionContextDecorator sessionCtxDecorator,
            FilterUsageNotifier usageNotifier,
            RequestCompleteHandler reqCompleteHandler,
            Registry registry,
            DirectMemoryMonitor directMemoryMonitor,
            EventLoopGroupMetrics eventLoopGroupMetrics,
            EurekaClient discoveryClient,
            ApplicationInfoManager applicationInfoManager,
            AccessLogPublisher accessLogPublisher) {

        super(
            serverStatusManager,
            filterLoader,
            sessionCtxDecorator,
            usageNotifier,
            reqCompleteHandler,
            registry,
            directMemoryMonitor,
            eventLoopGroupMetrics,
            new DefaultEventLoopConfig(),
            discoveryClient,
            applicationInfoManager,
            accessLogPublisher);
    }

    @Override
    protected Map<NamedSocketAddress, ChannelInitializer<?>> chooseAddrsAndChannels(ChannelGroup clientChannels) {
        Map<NamedSocketAddress, ChannelInitializer<?>> addrsToChannels = new HashMap<>();

        // Check if SSL is enabled (requires both cert and key files)
        // Environment variables take precedence over properties
        String certPath = getConfigValue("ZUUL_SSL_CERT_PATH", "zuul.server.ssl.cert.path", "");
        boolean sslEnabled = !certPath.isEmpty() && new File(certPath).exists();

        // Get port from configuration (default 9091 for HTTP, 8443 for HTTPS)
        int defaultPort = sslEnabled ? 8443 : 9091;
        String portStr = getConfigValue("ZUUL_SERVER_PORT", "zuul.server.port.main", String.valueOf(defaultPort));
        int port = Integer.parseInt(portStr);
        SocketAddress sockAddr = new SocketAddressProperty("zuul.server.addr.main", "=" + port).getValue();

        String metricId;
        if (sockAddr instanceof InetSocketAddress) {
            metricId = String.valueOf(((InetSocketAddress) sockAddr).getPort());
        } else {
            metricId = sockAddr.toString();
        }

        String mainListenAddressName = "main";
        ChannelConfig channelConfig = defaultChannelConfig(mainListenAddressName);
        ChannelConfig channelDependencies = defaultChannelDependencies(mainListenAddressName);

        // Configure for running behind load balancer/reverse proxy
        channelConfig.set(
            CommonChannelConfigKeys.allowProxyHeadersWhen,
            StripUntrustedProxyHeadersHandler.AllowWhen.ALWAYS);
        channelConfig.set(CommonChannelConfigKeys.preferProxyProtocolForClientIp, false);
        channelConfig.set(CommonChannelConfigKeys.withProxyProtocol, false);

        if (sslEnabled) {
            // Configure SSL using PEM certificate and key files
            String keyPath = getConfigValue("ZUUL_SSL_KEY_PATH", "zuul.server.ssl.key.path", "");
            File certFile = new File(certPath);
            File keyFile = new File(keyPath);

            if (!keyFile.exists()) {
                throw new IllegalStateException("SSL key file not found: " + keyPath);
            }

            log.info("SSL enabled, configuring HTTPS with cert: {}, key: {}", certPath, keyPath);

            ServerSslConfig sslConfig = ServerSslConfig.withDefaultCiphers(certFile, keyFile, "TLSv1.2", "TLSv1.3");

            channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);
            channelConfig.set(CommonChannelConfigKeys.sslContextFactory,
                new BaseSslContextFactory(registry, sslConfig));

            addrsToChannels.put(
                new NamedSocketAddress("https", sockAddr),
                new Http1MutualSslChannelInitializer(metricId, channelConfig, channelDependencies, clientChannels));

            log.info("Zuul Consul server configured to listen on HTTPS port {}", port);
        } else {
            // HTTP mode
            channelConfig.set(CommonChannelConfigKeys.isSSlFromIntermediary, false);

            addrsToChannels.put(
                new NamedSocketAddress("http", sockAddr),
                new ZuulServerChannelInitializer(metricId, channelConfig, channelDependencies, clientChannels));

            log.info("Zuul Consul server configured to listen on HTTP port {}", port);
        }

        logAddrConfigured(sockAddr);

        return Collections.unmodifiableMap(addrsToChannels);
    }

    /**
     * Get configuration value, checking environment variable first, then property, then default.
     *
     * @param envVar       environment variable name (e.g., ZUUL_SSL_CERT_PATH)
     * @param propertyName property name (e.g., zuul.server.ssl.cert.path)
     * @param defaultValue default value if neither is set
     * @return the configuration value
     */
    private static String getConfigValue(String envVar, String propertyName, String defaultValue) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        // Fall back to property
        return new DynamicStringProperty(propertyName, defaultValue).get();
    }
}
