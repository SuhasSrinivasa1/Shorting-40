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

rootProject.name = "Shorting40"
// Legacy app sources remain in the repository as an immutable implementation reference.
// They are NOT built or packaged into the short-only Android application.
include(":shorting")
