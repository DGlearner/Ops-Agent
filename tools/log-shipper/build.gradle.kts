// Ships log files to ingest-service, e.g. the shop's JSON logs (docs/shop-design.md §12.2):
//   ./gradlew :tools:log-shipper:run --args="--dir logs/shop"
plugins {
    id("opsagent.java-conventions")
    application
}

dependencies {
    implementation(libs.jackson.databind)

    testImplementation("org.awaitility:awaitility")
}

application {
    mainClass = "io.github.dglearner.opsagent.shipper.LogShipper"
}

tasks.named<JavaExec>("run") {
    // Relative paths such as --dir logs/shop resolve against the repository root.
    workingDir = layout.settingsDirectory.asFile
    maxHeapSize = "128m"
}
