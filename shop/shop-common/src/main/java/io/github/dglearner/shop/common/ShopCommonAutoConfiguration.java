package io.github.dglearner.shop.common;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Registers the shared error handling and the trace id response header in every shop service. */
@AutoConfiguration
public class ShopCommonAutoConfiguration {

    @Bean
    ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    FilterRegistrationBean<TraceIdResponseFilter> traceIdResponseFilter(ObjectProvider<Tracer> tracer) {
        FilterRegistrationBean<TraceIdResponseFilter> registration =
                new FilterRegistrationBean<>(new TraceIdResponseFilter(tracer));
        // Spring Boot's ServerHttpObservationFilter runs at HIGHEST_PRECEDENCE + 1 and opens the request span.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
