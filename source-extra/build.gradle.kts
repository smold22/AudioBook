import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":source-api"))
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.rhino)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}