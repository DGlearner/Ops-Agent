// The lab console (docs/shop-design.md §15): traffic, faults and their ground truth, to exercise Ops-Agent by hand.
//   ./gradlew :shop:lab:bootRun     then open http://localhost:8090
plugins {
    id("opsagent.spring-boot-service")
}

dependencies {
    implementation(project(":shop:load-generator"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // runs/lab/ground-truth.jsonl resolves against the repository root, next to the generator's reports.
    workingDir = layout.settingsDirectory.asFile
    maxHeapSize = "256m"
}
