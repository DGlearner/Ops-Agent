package io.github.dglearner.opsagent.core.remediation;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code ops.lab}: how to reach the shop lab (docs/agent-design.md §13.2).
 *
 * @param containers         the catalog's names of the containers that may be started, and their docker names
 * @param mysqlContainer     the docker name of the shop's MySQL, for its transactions
 * @param mysqlRootPassword  its administrator password (development only)
 * @param orderManagement    order-service's management port, for its switches
 * @param commandTimeout     how long a docker or HTTP call may take
 * @param verifyWindow       how long after an action its verification looks at alerts and logs
 * @param verifyGrace        what happens within this time of the action is not held against it (calls in flight)
 * @param leaseDuration      how long a worker holds a verification it claimed
 */
@ConfigurationProperties("ops.lab")
public record LabProperties(
        Map<String, String> containers,
        @DefaultValue("ops-shop-mysql-1") String mysqlContainer,
        @DefaultValue("shop-root-dev") String mysqlRootPassword,
        @DefaultValue("http://127.0.0.1:8191") URI orderManagement,
        @DefaultValue("20s") Duration commandTimeout,
        @DefaultValue("2m") Duration verifyWindow,
        @DefaultValue("15s") Duration verifyGrace,
        @DefaultValue("60s") Duration leaseDuration) {

    public LabProperties {
        containers = containers == null ? Map.of() : Map.copyOf(containers);
    }
}
