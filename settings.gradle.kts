pluginManagement {
    repositories {
        google()
        maven { url = uri("https://googleapis.com") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://googleapis.com") }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "QwenPawAndroid"
include(":app")
