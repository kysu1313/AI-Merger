plugins {
    kotlin("jvm") version "2.2.10"              // <-- bump to 2.2.x
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        rider("2025.2") {
            useInstaller.set(false)             // <-- required for Rider target right now
        }
        bundledPlugin("Git4Idea")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Keep Kotlin on Java 17 too
kotlin {
    jvmToolchain(17)
    // (Optional) lock language/api level if you want:
    // compilerOptions {
    //     languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    //     apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    // }
}

tasks.runIde {
    environment("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"))
}
