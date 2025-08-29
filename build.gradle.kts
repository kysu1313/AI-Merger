plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        rider("2025.2") {
            useInstaller.set(false)
        }
        bundledPlugin("Git4Idea")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.runIde {
    environment("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"))
}
