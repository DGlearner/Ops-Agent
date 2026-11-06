plugins {
    id("opsagent.shop-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Flash sales: who may buy is decided in Redis, and accepted orders queue in a Redis stream (§16).
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // The product cache's first level, in each instance (§17).
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")
}
