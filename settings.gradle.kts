// bugsplat-android 9.x: the Kotlin SDK lives in bugsplat-native/bindings/kotlin (git submodule);
// this repository packages, tests and publishes it, and carries the example app.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bugsplat-android"
include(":bugsplat")
project(":bugsplat").projectDir = file("bugsplat-native/bindings/kotlin")
include(":example")
project(":example").projectDir = file("bugsplat-native/samples/android/app")
