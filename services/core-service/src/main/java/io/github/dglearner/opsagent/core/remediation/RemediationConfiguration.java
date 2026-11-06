package io.github.dglearner.opsagent.core.remediation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabProperties.class)
class RemediationConfiguration {
}
