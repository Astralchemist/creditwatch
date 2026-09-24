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

                // Without a Developer ID signature jpackage falls back to an ad-hoc one, which
                // carries no team identity and no notarization ticket. That build runs here,
                // because a locally produced file is never quarantined, and is refused on every
                // machine that downloads it. Signing is therefore opt-in through credentials
                // that live outside the repository: configure them and the release is
                // distributable, leave them unset and packageDmg still produces the ad-hoc
                // build a contributor needs. See README, "Signing and notarization".
                val signingIdentity = providers.gradleProperty("compose.desktop.mac.signing.identity")
                    .orElse(providers.environmentVariable("CREDITWATCH_SIGNING_IDENTITY"))

                signing {
                    // The plugin adds --options runtime --timestamp itself, so the bundle is
                    // hardened; notarization rejects anything that is not. Its default
                    // entitlements already grant the three the JVM cannot start without
                    // (allow-jit, allow-unsigned-executable-memory, disable-library-validation),
                    // which is why no entitlements file is set here.
                    sign.set(signingIdentity.map { it.isNotBlank() }.orElse(false))
                    identity.set(signingIdentity)
                }

                notarization {
                    // An app-specific password, not the Apple ID password. notarizeDmg submits
                    // the signed .dmg to Apple and staples the returned ticket into it, so the
                    // installed copy proves itself without the machine being online.
                    appleID.set(
                        providers.gradleProperty("compose.desktop.mac.notarization.appleID")
                            .orElse(providers.environmentVariable("CREDITWATCH_NOTARIZATION_APPLE_ID"))
                    )
                    password.set(
                        providers.gradleProperty("compose.desktop.mac.notarization.password")
                            .orElse(providers.environmentVariable("CREDITWATCH_NOTARIZATION_PASSWORD"))
                    )
                    teamID.set(
                        providers.gradleProperty("compose.desktop.mac.notarization.teamID")
                            .orElse(providers.environmentVariable("CREDITWATCH_NOTARIZATION_TEAM_ID"))
                    )
                }
            }
            linux {
                iconFile.set(project.file("icons/creditwatch.png"))
            }
        }
    }
}
