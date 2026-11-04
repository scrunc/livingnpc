plugins {
    `java`
}

group = "dev.servereer"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")        // PacketEvents
    maven("https://maven.maxhenkel.de/repository/public/")            // Simple Voice Chat API
    maven("https://repo.extendedclip.com/releases/")                  // PlaceholderAPI
    maven("https://maven.enginehub.org/repo/")                        // WorldGuard (shop region gate)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.illustro:picore:1.2.0")
    // Server-provided PacketEvents — becomes a plugin `depend` at P1 (packet-NPC bodies).
    // Not shaded: a second PacketEvents instance would fight the server's packet injection.
    // 2.13.0 (was 2.12.0) for MC 26.x: the client's attack is a separate packet there
    // (PacketType.Play.Client.ATTACK / WrapperPlayClientAttack), which 2.12.0 has no name for.
    // NpcInteractListener only touches those on a 26.1+ server, so a 2.12.0 server still runs fine.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    // The audio-core companion plugin's API (VoiceCore/VoiceHandle). compileOnly + a plugin `depend` in
    // plugin.yml: the interface is resolved at runtime from the LivingShopkeeperVoice plugin's classloader
    // (NOT bundled here) so both plugins share the exact same type — the SVC connection lives over there.
    compileOnly(project(":voicecore"))
    // PixelAudio (plugin_customized/pixelaudio) — the SHARED Simple Voice Chat core, preferred over the
    // voicecore sibling above and the one KOTH/VideoWall/ArcadeCab/MachineConstruct already speak through.
    // compileOnly + a plugin `softdepend`: resolved at runtime from PixelAudio's own classloader (NOT
    // bundled) so both plugins share the exact same types. Gradle build outside this module, so it is
    // consumed as a file — run `./gradlew jar` in plugin_customized/pixelaudio/source if the jar is stale.
    compileOnly(files(rootProject.projectDir.resolve("../../pixelaudio/source/build/libs/PixelAudio-0.1.0.jar")))
    // JLayer — pure-Java MP3 decoder so ElevenLabs .mp3 clips play without conversion. Provided at RUNTIME
    // by Paper's `libraries:` loader (see plugin.yml), so compileOnly here — decoded via JLayer's API
    // directly (not the javax.sound SPI, which is classloader-fragile under Paper's isolated loaders).
    compileOnly("com.googlecode.soundlibs:jlayer:1.0.1.4")
    // PlaceholderAPI (soft-depend) — provides %livingshopkeeper_voicechat% etc. Guarded at runtime.
    compileOnly("me.clip:placeholderapi:2.11.6")
    // WorldGuard (soft-depend) — shop placement is gated to regions the placer owns (ARM plots are WG
    // regions), and "a customer is in the shop" means inside the shop's region. Guarded in RegionGate.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    // Vault (economy, P4) joins here as compileOnly when that phase lands.

    // Unit tests (GossipPlaceholders / GossipCtx / DialogText.renderGossip) use JUnit 5 (Jupiter). Without a
    // declared test framework Gradle falls back to JUnit 4 and the `test` task fails to start ("Failed to load
    // JUnit 4"). The API jar is added to the test classpath too — never a running server, but the dialog and
    // condition classes reference World/Player in their signatures, so the classes have to link under test.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain {
        // 21 — no Foreign Function API needed; runs on the server's 25 runtime.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("LivingNPC")
}
