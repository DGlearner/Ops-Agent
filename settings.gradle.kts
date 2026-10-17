pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads a matching JDK when the requested toolchain (Java 21) is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ops-agent"

include(
    "libs:ops-contracts",
    "services:ingest-service",
    "services:stream-processor",
    "shop:shop-common",
    "shop:inventory-service",
    "shop:payment-service",
    "shop:order-service",
    "shop:load-generator",
    "tools:log-shipper",
)
