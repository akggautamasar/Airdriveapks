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

// The Telegram half compiles against TDLib's generated Java API. tdlib/td publishes no binaries, so
// that jar is cross-built from the same commit the phone app pins (see
// desktop/tools/build-tdlib-windows.sh) and committed here as desktop/libs/tdlib.jar, which is what
// keeps an ordinary build offline: nothing is downloaded to compile, and the one jar in play is
// generated from a pinned source rather than fetched from a repository.
val tdlibJar = file("libs/tdlib.jar")
if (!tdlibJar.isFile) {
    throw GradleException(
        "desktop/libs/tdlib.jar is missing. It holds org.drinkless.tdlib.Client and the generated " +
            "TdApi that the upload code compiles against. Rebuild it with " +
            "desktop/tools/build-tdlib-windows.sh, or fetch it with sh desktop/tools/fetch-tdlib-windows.sh."
    )
}

// The native half is tens of megabytes, so it is not committed; the packaging step takes tdjni.dll out
// of the TDLib release and puts it beside the jar, which is where the code looks for it. A build
// without it still compiles and still backs up to folders; uploading reports itself unavailable, which
// is what --tg-probe is there to say out loud.
dependencies {
    // The one jar in this build, and it is generated from a pinned source rather than fetched: no
    // repository is consulted for it. Everything else is JDK Swing and java.nio, no tests, so the jar
    // is the whole deliverable.
    implementation(files(tdlibJar))
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
