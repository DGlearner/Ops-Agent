package io.github.dglearner.shop.order.client;

import io.github.dglearner.shop.order.OrderProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP clients for inventory and payment, each with its own connect and read timeout. They are built from Spring
 * Boot's {@code RestClient.Builder}, which records {@code http.client.requests} metrics and sends the W3C
 * {@code traceparent} header, so a trace continues across services.
 */
@Configuration(proxyBeanMethods = false)
public class DownstreamClients {

    @Bean
    InventoryClient inventoryClient(RestClient.Builder builder, OrderProperties properties, JsonMapper json) {
        return new InventoryClient(client(builder, properties.inventory()), json);
    }

    @Bean
    PaymentClient paymentClient(RestClient.Builder builder, OrderProperties properties, JsonMapper json) {
        return new PaymentClient(client(builder, properties.payment()), json);
    }

    private static RestClient client(RestClient.Builder builder, OrderProperties.Downstream downstream) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(downstream.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(downstream.readTimeout());
        return builder.clone().baseUrl(downstream.baseUrl()).requestFactory(factory).build();
    }
}
