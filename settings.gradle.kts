pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "Comics8"
include(":core")
include(":desktop")
val desktopOnly =
    settings.startParameter.projectProperties["comics8.desktopOnly"] == "true" ||
        System.getenv("COMICS8_DESKTOP_ONLY") == "1"
if (!desktopOnly) {
    include(":app")
}
