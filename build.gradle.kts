plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        rider("2025.1") {
            useInstaller.set(false)
        }
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Multi-Repo Merger"
        version = "0.1.0"
        ideaVersion {
            sinceBuild.set("251.0")
            untilBuild.set("251.*")
        }
        vendor {
            name = "Kyle Supple"
            email = "kylems3376@gmail.com"
            url = "https://code-nerd.com"
        }
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
kotlin {
    jvmToolchain(17)
}

tasks.runIde {
    val k = System.getenv("OPENAI_API_KEY")
    if (k != null) {
        environment("OPENAI_API_KEY", k)
        jvmArgs("-DOPENAI_API_KEY=$k")
    }
}
