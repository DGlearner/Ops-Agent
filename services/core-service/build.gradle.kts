// Ops-Agent's own backend: alerts, then incidents, rules, catalog and identity (docs/project-design.md §6.4).
plugins {
    id("opsagent.spring-boot-service")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:ops-contracts"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.mybatis.plus.boot.starter)
    implementation(libs.springdoc.webmvc.ui)
    // Events between modules are recorded in the same transaction as the change that raised them, and delivered
    // again after a crash (event_publication table).
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.awaitility:awaitility")
}
