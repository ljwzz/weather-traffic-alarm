plugins {
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":domain"))

    testImplementation(libs.spring.boot.starter.test)
}
