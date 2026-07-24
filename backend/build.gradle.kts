plugins {
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "java")

    group = "com.ljwzz.weathertrafficalarm.backend"
    version = "0.1.0"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.release = 21
    }
}
