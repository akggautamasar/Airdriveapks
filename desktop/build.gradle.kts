plugins {
    kotlin("jvm") version "1.9.24"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Deliberately nothing: a PC build with no third-party jars is one that keeps working when a
    // repository goes away. Everything below is JDK Swing and java.nio. No tests either, so the
    // jar is the whole deliverable.
}

application {
    mainClass.set("com.airdrive.pc.MainKt")
    // Swing scales itself against the monitor DPI; without this the window is tiny on a laptop.
    applicationDefaultJvmArgs = listOf("-Dsun.java2d.uiScale=2.0")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.airdrive.pc.MainKt"
        attributes["Implementation-Title"] = "AirDrive for PC"
    }
}
