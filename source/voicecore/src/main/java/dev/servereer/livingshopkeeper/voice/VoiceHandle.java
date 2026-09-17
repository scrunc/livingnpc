package dev.servereer.livingshopkeeper.voice;

/** Opaque stop handle for a running voice stream — callers never touch Simple Voice Chat types. */
public interface VoiceHandle {
    void stop();
}
