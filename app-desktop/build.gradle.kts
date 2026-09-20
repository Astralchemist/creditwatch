plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-engine"))
    implementation(project(":provider-api"))
    implementation(project(":provider-vast"))
    implementation(project(":persistence"))
    implementation(project(":notifications"))
    implementation(project(":telemetry-api"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:3.6.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "dev.creditwatch.app.MainKt"
        nativeDistributions {
            packageName = "CreditWatch"
            packageVersion = "0.1.0"
        }
    }
}
