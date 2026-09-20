pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "creditwatch"

include(
    "app-desktop",
    "core-domain",
    "core-engine",
    "provider-api",
    "provider-vast",
    "persistence",
    "notifications",
    "telemetry-api",
    "telemetry-agent",
    "test-fixtures",
)
