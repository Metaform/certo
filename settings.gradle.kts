plugins {
    // Lets the Gradle toolchain auto-provision the JDK 25 toolchain if it isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "certo"
