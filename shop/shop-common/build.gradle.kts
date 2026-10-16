// Shared by the three shop services: web stack, tracing, error handling and default configuration.
plugins {
    id("opsagent.java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // Trace ids for log correlation and W3C traceparent propagation. Nothing is exported.
    api("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    api("io.micrometer:micrometer-tracing-bridge-otel")
    // DataAccessException types, so database outages map to 503 in ApiExceptionHandler.
    api("org.springframework:spring-tx")

    // ShopMySql: the MySQL container every shop service's integration tests use.
    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.testcontainers.mysql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.test {
    systemProperty("shop.log-dir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
}
