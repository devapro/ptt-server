plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.github.devapro.pttdroid.server"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.github.devapro.pttdroid.server.MainKt")
}
