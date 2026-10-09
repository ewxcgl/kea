plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * The protocol lives here, deliberately free of Android.
 *
 * `kea-core` holds the wire format, the crypto, and the geodesy — none of which
 * need an Android runtime. That buys three things:
 *   - conformance tests run as plain JVM tests, in seconds, with no emulator
 *   - the same reference vectors can be ported to the Swift client unchanged
 *   - the cryptography has no framework in reach to quietly depend on
 *
 * The iOS client implements the same spec against CryptoKit; see ../PROTOCOL.md.
 */

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}