import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation("net.java.dev.jna:jna:5.19.1")
    // Pairing shows a QR code the phone scans; drawn from the matrix, no image files.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "dev.creditwatch.app.MainKt"
        nativeDistributions {
            packageName = "CreditWatch"
            packageVersion = project.version.toString()
            description = "Know how long your cloud credit will last."
            vendor = "CreditWatch"
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            macOS {
                // macOS ties notification identity, permission and icon to this bundle id.
                // Without it the app has no identity of its own and anything it posts is
                // attributed to the JVM rather than to CreditWatch.
                bundleID = "dev.creditwatch.app"
                iconFile.set(project.file("icons/creditwatch.icns"))
                // jpackage rejects a macOS version whose major component is zero, so the
                // bundle version is pinned while the project is still on 0.x.
                packageVersion = "1.0.0"
                packageBuildVersion = "1.0.0"
            }
            linux {
                iconFile.set(project.file("icons/creditwatch.png"))
            }
        }
    }
}
