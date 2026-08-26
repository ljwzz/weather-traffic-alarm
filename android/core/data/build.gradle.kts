plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.ljwzz.weathertrafficalarm.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.proto)
    implementation(libs.datastore.core)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
