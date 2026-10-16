package io.github.dglearner.shop.common;

import io.github.dglearner.shop.common.faults.FaultInjectionFilter;
import io.github.dglearner.shop.common.faults.FaultInjector;
import io.github.dglearner.shop.common.faults.FaultsEndpoint;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers the shared error handling, the trace id response header and fault injection in every shop service.
 *
 * <p>Runs before Spring MVC's auto-configuration: Boot adds its own problem-details handler only when no
 * {@code ResponseEntityExceptionHandler} exists yet, and would otherwise answer validation errors without a
 * {@code code}.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
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

    @Bean
    FaultInjector faultInjector() {
        return new FaultInjector();
    }

    @Bean
    FaultsEndpoint faultsEndpoint(FaultInjector injector) {
        return new FaultsEndpoint(injector);
    }

    /** Runs inside the server observation, so injected latency and errors show up in http.server.requests. */
    @Bean
    FilterRegistrationBean<FaultInjectionFilter> faultInjectionFilter(FaultInjector injector) {
        FilterRegistrationBean<FaultInjectionFilter> registration =
                new FilterRegistrationBean<>(new FaultInjectionFilter(injector));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
