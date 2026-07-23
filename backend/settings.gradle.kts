pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
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
