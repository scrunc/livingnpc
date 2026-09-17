package dev.servereer.livingshopkeeper.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes an MP3 (e.g. an ElevenLabs export) to mono float samples via JLayer's decoder API directly —
 * no {@code javax.sound} SPI (which doesn't reliably register under Paper's isolated plugin classloaders).
 * {@link VoiceClip} resamples the result to 48 kHz. JLayer is provided at runtime by Paper's
 * {@code libraries:} loader (see plugin.yml).
 */
final class Mp3Decoder {

    private Mp3Decoder() {}

    /** Decode {@code mp3} to mono float PCM; {@code rateOut[0]} receives the source sample rate (Hz). */
    static float[] decodeMono(File mp3, float[] rateOut) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(mp3))) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            List<float[]> chunks = new ArrayList<>();
            int total = 0;
            float rate = 44_100f;
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] pcm = out.getBuffer();
                int len = out.getBufferLength();          // interleaved sample count
                int ch = Math.max(1, out.getChannelCount());
                rate = out.getSampleFrequency();
                int frames = len / ch;
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < ch; c++) acc += pcm[i * ch + c];
                    mono[i] = (float) acc / ch;
                }
                chunks.add(mono);
                total += frames;
                bitstream.closeFrame();
            }
            bitstream.close();

            float[] all = new float[total];
            int off = 0;
            for (float[] c : chunks) { System.arraycopy(c, 0, all, off, c.length); off += c.length; }
            rateOut[0] = rate > 0 ? rate : 44_100f;
            return all;
        }
    }
}
