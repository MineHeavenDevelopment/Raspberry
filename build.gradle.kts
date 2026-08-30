
plugins {
    kotlin("jvm") version "2.3.21"
    application
    kotlin("plugin.serialization") version "2.3.21"
}

group = "ir.nayragames"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"

dependencies {
    // Kotlin test (resolves the junit5 variant via capability) + JUnit platform engine
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    implementation("org.json:json:20240303")
    implementation("redis.clients:jedis:7.2.0")
    implementation("com.akuleshov7:ktoml-core:0.5.1")
    implementation("com.akuleshov7:ktoml-file:0.5.1")
    implementation("net.dv8tion:JDA:5.0.0-beta.20")

    // Core & Netty Engine
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")

    // Content Negotiation & Serialization (JSON)
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // Authentication & Authorization (JWT)
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")

    // Logging & CORS
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")

    // Logging Implementation (SLF4J / Logback)
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")

    // Client Core & Engine (CIO)
    implementation("io.ktor:ktor-client-core-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-client-cio-jvm:${ktorVersion}")

    // Client Serialization
    implementation("io.ktor:ktor-client-content-negotiation-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${ktorVersion}")

    // Client Logging
    implementation("io.ktor:ktor-client-logging-jvm:${ktorVersion}")
}

application {
    mainClass.set("ir.nayragames.MainKt")
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
