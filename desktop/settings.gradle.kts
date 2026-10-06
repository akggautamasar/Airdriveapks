pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Standalone on purpose: the phone app needs the Android SDK and KSP, this one only a JDK, so
// neither build can break the other. Run it from the repo root with:  gradlew -p desktop distZip
rootProject.name = "airdrive-pc"
