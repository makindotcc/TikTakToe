import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.20"
    application
}

group = "cc.makin"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("cc.makin.tiktaktoe.TikTakToeKt")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("dev.kord:kord-core:0.8.0-M14")
    implementation("org.apache.logging.log4j:log4j-api:2.17.2")
    implementation("org.apache.logging.log4j:log4j-core:2.17.2")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.17.2")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("io.ktor:ktor-serialization-gson:2.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
}
