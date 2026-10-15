package io.github.dglearner.opsagent.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of log source; selects the parser in stream-processor. */
public enum SourceType {
    @JsonProperty("nginx") NGINX,
    @JsonProperty("linux") LINUX,
    @JsonProperty("mysql") MYSQL,
    @JsonProperty("app_service") APP_SERVICE,
    @JsonProperty("docker") DOCKER
}
