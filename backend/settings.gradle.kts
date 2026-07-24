pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "weather-traffic-alarm-backend"

include(":app")
include(":domain")
include(":provider-amap")
include(":provider-caiyun")
include(":persistence")
