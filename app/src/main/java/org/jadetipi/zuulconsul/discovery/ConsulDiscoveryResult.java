package org.jadetipi.zuulconsul.discovery;

import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInfo;
import com.netflix.zuul.discovery.DiscoveryResult;
import org.jadetipi.zuulconsul.consul.ConsulService;

/**
 * Factory for creating DiscoveryResult instances from ConsulService.
 * <p>
 * This class creates a bridge between our Consul service discovery
 * and Zuul's Ribbon/Eureka-based discovery infrastructure.
 */
public final class ConsulDiscoveryResult {

    private ConsulDiscoveryResult() {
        // Utility class
    }

    /**
     * Create a DiscoveryResult from a Consul service.
     *
     * @param consulService the Consul service
     * @return a DiscoveryResult that wraps the Consul service info
     */
    public static DiscoveryResult from(ConsulService consulService) {
        InstanceInfo instanceInfo = createInstanceInfo(consulService);
        return DiscoveryResult.from(instanceInfo, false);
    }

    /**
     * Create an InstanceInfo from a ConsulService.
     */
    private static InstanceInfo createInstanceInfo(ConsulService consulService) {
        return InstanceInfo.Builder.newBuilder()
            .setAppName(consulService.getName())
            .setInstanceId(consulService.getId())
            .setHostName(consulService.getAddress())
            .setIPAddr(consulService.getAddress())
            .setPort(consulService.getPort())
            .setDataCenterInfo(new MyDataCenterInfo(DataCenterInfo.Name.MyOwn))
            .setVIPAddress(consulService.getName())
            .setStatus(InstanceInfo.InstanceStatus.UP)
            .build();
    }
}
