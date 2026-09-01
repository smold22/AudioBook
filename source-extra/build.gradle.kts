import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.jar.JarFile

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
    testImplementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    testImplementation("org.libtorrent4j:libtorrent4j-windows:2.1.0-35")
}

tasks.withType<Test> {
    val nativeDir = layout.buildDirectory.dir("nativeLibs")
    doFirst {
        val dir = nativeDir.get().asFile
        dir.mkdirs()
        val dll = dir.resolve("libtorrent4j.dll")
        if (!dll.exists()) {
            val jar = configurations.testRuntimeClasspath.get().first { it.name.contains("libtorrent4j-windows") }
            JarFile(jar).use { jf ->
                jf.getInputStream(jf.getEntry("lib/x86_64/libtorrent4j.dll")).use { input ->
                    dll.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
    systemProperty("libtorrent4j.jni.path", nativeDir.get().asFile.resolve("libtorrent4j.dll").absolutePath)
}