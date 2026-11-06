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

// How well real log files fit the parsers (docs/project-design.md §8.9), e.g.
// ./gradlew :services:stream-processor:parseFit --args="linux:auth:/var/log/auth.log* nginx:nginx:/var/log/nginx/*.log*"
tasks.register<JavaExec>("parseFit") {
    group = "verification"
    description = "Runs real log files through the parsers and reports what fits"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.dglearner.opsagent.stream.parse.ParseFit"
    maxHeapSize = "512m"
}
