// Shared settings for every Java project: toolchain, compiler flags, test setup and the Spring Boot BOM.
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(lib("spring-boot-dependencies")))
    testImplementation(platform(lib("spring-boot-dependencies")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-this-escape"))
}

// Integration tests start Kafka, MySQL or Redis containers. Running two test tasks side by side doubles that, which
// is what exhausted memory on a small dev server, so test tasks take turns; compilation stays parallel.
val testSlot = gradle.sharedServices.registerIfAbsent("testSlot", TestSlot::class) {
    maxParallelUsages = 1
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    usesService(testSlot)
    maxHeapSize = "512m"
    jvmArgs("-XX:MaxMetaspaceSize=256m", "-XX:+UseSerialGC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
