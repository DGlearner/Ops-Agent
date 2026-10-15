package io.github.dglearner.opsagent.ingest.config;

import io.github.dglearner.opsagent.ingest.api.RequestSizeLimitFilter;
import io.github.dglearner.opsagent.ingest.auth.ApiKeyInterceptor;
import io.github.dglearner.opsagent.ingest.auth.SourceRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class IngestWebConfiguration implements WebMvcConfigurer {

    private final SourceRegistry sources;

    public IngestWebConfiguration(SourceRegistry sources) {
        this.sources = sources;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(sources)).addPathPatterns("/api/**");
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(IngestProperties properties) {
        var registration = new FilterRegistrationBean<>(
                new RequestSizeLimitFilter(properties.maxRequestSize().toBytes()));
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
