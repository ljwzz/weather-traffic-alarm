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
        maven("https://repo1.maven.org/maven2")
    }
}

rootProject.name = "weather-traffic-alarm"

include(":app")
include(":core:model")
include(":core:data")
include(":core:network")
include(":core:alarm")
include(":core:map")
include(":feature:onboarding")
include(":feature:home")
include(":feature:plan")
include(":feature:place")
include(":feature:calendar")
include(":feature:history")
include(":feature:diagnostics")
