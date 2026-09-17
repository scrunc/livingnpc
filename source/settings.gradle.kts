rootProject.name = "livingnpc"

// The persistent audio-core companion plugin (LivingShopkeeperVoice.jar). Owns the Simple Voice Chat
// connection so the main plugin can PlugMan-reload without muting NPCs.
include("voicecore")
