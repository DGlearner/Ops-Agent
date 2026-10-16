// Command-line request generator for the shop (docs/shop-design.md §9):
//   ./gradlew :shop:load-generator:run --args="--rate 10 --duration 60s"
plugins {
    id("opsagent.java-conventions")
    application
}

dependencies {
    implementation(libs.jackson.databind)

    testImplementation("org.awaitility:awaitility")
}

application {
    mainClass = "io.github.dglearner.shop.loadgen.LoadGenerator"
}

tasks.named<JavaExec>("run") {
    // Relative paths such as --report runs/x.json resolve against the repository root.
    workingDir = layout.settingsDirectory.asFile
    maxHeapSize = "256m"
}
