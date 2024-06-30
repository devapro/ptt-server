plugins {
    kotlin("jvm") version "1.9.23"
    application
}

group = "com.github.devapro.pttdroid.server"
version = "1.0-SNAPSHOT"

val ktorVersion = "2.3.8"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")


    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.github.devapro.pttdroid.server.MainKt")
}