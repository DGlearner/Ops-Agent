plugins {
    id("opsagent.spring-boot-service")
}

dependencies {
    implementation(project(":libs:ops-contracts"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation(libs.kafka.streams)

    testImplementation(libs.kafka.streams.test.utils)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.kafka)
}

val datasetDir = layout.settingsDirectory.dir("datasets")

tasks.test {
    inputs.dir(datasetDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("datasets.dir", datasetDir.asFile.absolutePath)
}
