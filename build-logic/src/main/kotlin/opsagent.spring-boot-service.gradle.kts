// Conventions for a deployable Spring Boot service.
plugins {
    id("opsagent.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

springBoot {
    // Exposes version information at /actuator/info; the build time is left out to keep builds cacheable.
    buildInfo {
        excludes = setOf("time")
    }
}

// A bounded heap for local runs, so a few services plus the dev stack fit on a small machine.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    maxHeapSize = "512m"
}

// Services ship as executable jars only; skip the plain jar.
tasks.named<Jar>("jar") {
    enabled = false
}
