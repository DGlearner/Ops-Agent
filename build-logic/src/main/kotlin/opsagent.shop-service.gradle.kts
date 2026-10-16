// Conventions for the shop target services (docs/shop-design.md).
plugins {
    id("opsagent.spring-boot-service")
}

dependencies {
    implementation(project(":shop:shop-common"))
}

// Every service writes its JSON log to <repo>/logs/shop, so a collector finds them in one place.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("shop.log-dir", layout.settingsDirectory.dir("logs/shop").asFile.absolutePath)
    jvmArgs("-Xmx256m")
}

tasks.withType<Test>().configureEach {
    systemProperty("shop.log-dir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
}
