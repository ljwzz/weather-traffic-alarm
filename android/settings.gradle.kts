pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
        maven("https://repo1.maven.org/maven2")
    }
}

rootProject.name = "weather-traffic-alarm"

include(":app")
