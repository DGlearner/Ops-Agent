package io.github.dglearner.shop.order.catalog;

import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.redis.RedisFailureLog;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * The cache's Redis plumbing: the channel every instance listens on to drop local copies, and loading the Bloom filter
 * at startup. Neither may keep the service from starting while Redis is down, since regular orders do not need Redis:
 * the listener subscribes from a background thread and keeps trying (until then, local copies of another instance's
 * changes expire within their 5 s), and the filter is loaded later when a check finds it missing.
 */
@Configuration(proxyBeanMethods = false)
class ProductCacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheConfiguration.class);
    private static final long RETRY_MILLIS = 2000;

    @Bean
    RedisMessageListenerContainer productInvalidationListener(RedisConnectionFactory connections, ProductCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setRecoveryInterval(RETRY_MILLIS);
        // Started by productInvalidationSubscriber: starting here subscribes synchronously, and fails the whole
        // application when Redis cannot be reached (it did, on a busy machine with a 500 ms connect timeout). Our own
        // container, next to the one Spring Boot provides for annotated listeners, which stays empty.
        container.setAutoStartup(false);
        container.addMessageListener((message, pattern) -> cache.announced(
                new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(ProductCache.CHANNEL));
        return container;
    }

    @Bean
    ApplicationRunner productInvalidationSubscriber(
            @Qualifier("productInvalidationListener") RedisMessageListenerContainer container,
            RedisFailureLog redisFailures) {
        return args -> Thread.ofVirtual().name("product-invalidation-subscriber").start(() -> {
            while (!container.isRunning()) {
                try {
                    container.start();
                    log.atInfo().addKeyValue(LogEvents.ACTION, "product_invalidations_subscribed")
                            .log("Subscribed to product invalidations");
                } catch (DataAccessException e) {
                    redisFailures.failed("subscribe", e);
                    pause();
                } catch (RuntimeException e) {
                    log.atWarn().addKeyValue(LogEvents.ACTION, "product_invalidations_subscribe_failed")
                            .addKeyValue("error_type", e.getClass().getSimpleName())
                            .log("Cannot subscribe to product invalidations yet: {}", e.getMessage());
                    pause();
                }
            }
        });
    }

    @Bean
    ApplicationRunner productBloomLoader(ProductCache cache) {
        return args -> Thread.ofVirtual().name("product-bloom-loader").start(cache::loadBloom);
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
