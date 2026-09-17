plugins {
    `java`
}

group = "dev.servereer"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public/")            // Simple Voice Chat API
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Simple Voice Chat server API — the core owns this connection so the main plugin never has to.
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("LivingShopkeeperVoice")
}
