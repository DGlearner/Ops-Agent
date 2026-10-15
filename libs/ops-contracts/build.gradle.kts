plugins {
    id("opsagent.java-conventions")
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    // Only for JsonSerde; services bring their own kafka-clients through Spring Kafka.
    compileOnly(libs.kafka.clients)

    testImplementation(libs.kafka.clients)
    testImplementation(libs.json.schema.validator)
}

val schemaDir = layout.settingsDirectory.dir("contracts/kafka")

tasks.test {
    inputs.dir(schemaDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("contracts.dir", schemaDir.asFile.absolutePath)
}
