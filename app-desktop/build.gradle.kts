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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    // Pairing shows a QR code the phone scans; drawn from the matrix, no image files.
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

compose.desktop {
    application {
        mainClass = "dev.creditwatch.app.MainKt"
        nativeDistributions {
            // jpackage trims the bundled runtime with jlink, and module detection cannot see a
            // dependency reached by reflection. The SQLite driver is loaded with Class.forName,
            // so java.sql was pruned and the installed app could not open its own database —
            // it launched, drew its window and silently monitored nothing. java.prefs is the
            // same story for the saved appearance and alert settings. This list comes from
            // `./gradlew :app-desktop:suggestRuntimeModules`; re-run it when dependencies change.
            modules("java.instrument", "java.management", "java.prefs", "java.sql", "jdk.unsupported")
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
                // jpackage refuses an app-version whose major component is zero, which is why
                // this project never shipped a 0.x: the bundle would have had to claim a
                // version the repository did not. Keep the major at 1 or above.
                packageVersion = project.version.toString()
                packageBuildVersion = project.version.toString()
            }
            linux {
                iconFile.set(project.file("icons/creditwatch.png"))
            }
        }
    }
}
