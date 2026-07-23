plugins {
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
}
