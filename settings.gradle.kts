pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "caravanserai"
include(":core", ":youtube", ":subsonic", ":app", ":dict-tool", ":desktop", ":desktop-subsonic")
// The Subsonic app on the desktop reuses :desktop's build script for the subsonic flavor (desktop/build.gradle.kts).
project(":desktop-subsonic").apply { projectDir = file("desktop/subsonic"); buildFileName = "../build.gradle.kts" }
