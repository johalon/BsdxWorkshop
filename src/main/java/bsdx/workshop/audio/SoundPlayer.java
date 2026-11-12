package bsdx.workshop.audio;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Plays SE / voice files (ogg via {@link OggDecoder}, wav via javax.sound) with a single shared Clip:
 * starting a new sound stops the previous one.
 */
public final class SoundPlayer {
    private SoundPlayer() {}
    private static Clip current;

    /** Decoded audio ready for javax.sound. */
    public static final class Decoded {
        public final AudioFormat format; public final byte[] pcm; public final String kind;
        Decoded(AudioFormat f, byte[] pcm, String kind) { this.format = f; this.pcm = pcm; this.kind = kind; }
        public double seconds() { return pcm.length / (double) format.getFrameSize() / format.getFrameRate(); }
        public AudioInputStream stream() { return new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize()); }
    }

    public static boolean isWav(byte[] b) { return b != null && b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A'; }

    /** ogg / wav bytes -> PCM (wav in non-PCM encodings is converted to signed 16-bit when possible). */
    public static Decoded decode(byte[] data) throws IOException {
        if (OggDecoder.isOgg(data)) {
            OggDecoder.Pcm p = OggDecoder.decode(data);
            return new Decoded(new AudioFormat(p.rate, 16, p.channels, true, false), p.data, "ogg");
        }
        if (isWav(data)) {
            try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))) {
                AudioFormat f = in.getFormat();
                AudioInputStream src = in;
                if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED && f.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                    AudioFormat t = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
                    src = AudioSystem.getAudioInputStream(t, in);
                    f = t;
                }
                return new Decoded(f, src.readAllBytes(), "wav");
            } catch (UnsupportedAudioFileException e) {
                throw new IOException("この wav 形式は再生できません: " + e.getMessage());
            }
        }
        throw new IOException("ogg / wav ではありません");
    }

    /** Starts playback (returns immediately). */
    public static synchronized Decoded play(byte[] data) throws IOException {
        Decoded d = decode(data);
        stop();
        try {
            Clip c = AudioSystem.getClip();
            c.open(d.format, d.pcm, 0, d.pcm.length);
            c.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) { synchronized (SoundPlayer.class) { if (current == c) current = null; } c.close(); } });
            current = c;
            c.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new IOException("サウンドデバイスを開けません: " + e.getMessage());
        }
        return d;
    }

    public static synchronized void stop() {
        if (current != null) { Clip c = current; current = null; c.stop(); c.close(); }
    }
}
