plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":provider-amap"))
    implementation(project(":provider-caiyun"))
    implementation(project(":persistence"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
