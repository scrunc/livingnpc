package dev.servereer.livingshopkeeper.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

/**
 * One spoken voice line, normalised to what Simple Voice Chat wants: 48 kHz, mono, signed 16-bit,
 * delivered in 20 ms frames of 960 samples. Ported from MachineConstruct's {@code AudioTrack}.
 *
 * <p>Loads <b>WAV</b> directly via {@code javax.sound.sampled} (pure Java — no ffmpeg), which is exactly
 * what Piper (and Windows SAPI, for quick tests) emit. Any input rate is linearly resampled to 48 kHz by
 * hand rather than trusting the JDK's optional sample-rate converters, which aren't guaranteed present.
 */
public final class VoiceClip {

    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SAMPLES = 960; // 20 ms @ 48 kHz

    private final short[] samples; // mono, 48 kHz

    private VoiceClip(short[] samples) {
        this.samples = samples;
    }

    public int totalSamples() {
        return samples.length;
    }

    public long durationMs() {
        return (long) samples.length * 1000L / SAMPLE_RATE;
    }

    /** True once {@code frameIndex} (20 ms units) has run past the end of the clip. */
    public boolean exhausted(long frameIndex) {
        return frameIndex * FRAME_SAMPLES >= samples.length;
    }

    /** The 960-sample frame at {@code frameIndex}, zero-padded past the end. */
    public short[] frameAt(long frameIndex, boolean loop) {
        short[] out = new short[FRAME_SAMPLES];
        if (samples.length == 0) return out;
        long start = frameIndex * FRAME_SAMPLES;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            long idx = start + i;
            if (loop) {
                out[i] = samples[(int) Math.floorMod(idx, samples.length)];
            } else if (idx < samples.length) {
                out[i] = samples[(int) idx];
            } else {
                break;
            }
        }
        return out;
    }

    /** Load a clip by extension: {@code .mp3} via JLayer, anything else as WAV via {@code javax.sound}. */
    public static VoiceClip load(File file) throws Exception {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) {
            float[] rate = new float[1];
            float[] mono = Mp3Decoder.decodeMono(file, rate);
            return new VoiceClip(resample(mono, rate[0], SAMPLE_RATE));
        }
        return loadWav(file);
    }

    private static VoiceClip loadWav(File wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat src = in.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcm, in)) {
                byte[] raw = pcmIn.readAllBytes();
                int ch = pcm.getChannels();
                int frames = raw.length / (2 * ch);
                float[] mono = new float[frames];
                for (int f = 0; f < frames; f++) {
                    int acc = 0;
                    for (int c = 0; c < ch; c++) {
                        int off = (f * ch + c) * 2;
                        short s = (short) ((raw[off] & 0xFF) | (raw[off + 1] << 8));
                        acc += s;
                    }
                    mono[f] = (float) acc / ch;
                }
                return new VoiceClip(resample(mono, src.getSampleRate(), SAMPLE_RATE));
            }
        }
    }

    private static short[] resample(float[] in, float srcRate, int dstRate) {
        if (in.length == 0) return new short[0];
        if (Math.abs(srcRate - dstRate) < 0.5f) {
            short[] out = new short[in.length];
            for (int i = 0; i < in.length; i++) out[i] = clamp(in[i]);
            return out;
        }
        double ratio = (double) dstRate / srcRate;
        int outLen = (int) Math.floor(in.length * ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i / ratio;
            int i0 = (int) Math.floor(srcPos);
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = srcPos - i0;
            out[i] = clamp((float) (in[i0] * (1 - frac) + in[i1] * frac));
        }
        return out;
    }

    private static short clamp(float v) {
        int s = Math.round(v);
        if (s > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (s < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) s;
    }
}
