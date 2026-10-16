package io.github.dglearner.shop.common;

import org.testcontainers.mysql.MySQLContainer;

/** The MySQL container for shop integration tests: same version as deploy/shop, tuned to stay small. */
public final class ShopMySql {

    public static MySQLContainer newContainer() {
        return new MySQLContainer("mysql:8.4.10")
                // Performance Schema alone takes ~200 MB; tests need neither it, a large buffer pool nor a binlog.
                .withCommand("mysqld", "--performance-schema=OFF", "--innodb-buffer-pool-size=32M", "--skip-log-bin")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));
    }

    private ShopMySql() {
    }
}
