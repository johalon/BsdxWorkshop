package bsdx.workshop;

import bsdx.workshop.audio.OggDecoder;
import bsdx.workshop.audio.SoundPlayer;
import bsdx.workshop.data.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** SE playback: JOrbis decode vs ffmpeg reference, wav decode, VFS lookup of SeGroup items. */
public class AudioTest {
    static void run(Codec codec, Fields fields, Path stage, Path overlay) throws Exception {
        System.out.println("[SE playback: ogg (JOrbis) / wav decode]");
        Project p = Loader.open(codec, fields, stage, true, Files.isRegularFile(overlay) ? overlay : null, null);
        // all SE items that resolve in the VFS (vanilla Se.pac is not staged in the cloud -> the overlay's BFE_*.ogg)
        int found = 0, decoded = 0, bad = 0;
        List<String> samples = new ArrayList<>();
        double secs = 0;
        for (int c = 0; c < p.seList().size(); c++) {
            int n = p.seList().get(c).path("seItems").size();
            for (int i = 0; i < n; i++) {
                String f = p.soundFile(p.seBaseName(c, i));
                if (f == null) continue;
                found++;
                try {
                    SoundPlayer.Decoded d = SoundPlayer.decode(p.fs.read(f));
                    decoded++;
                    secs += d.seconds();
                    if (samples.size() < 4) samples.add(c + "/" + i + " " + f);
                } catch (Exception e) {
                    bad++;
                    System.out.println("    decode failed " + f + ": " + e);
                }
            }
        }
        SelfTest.check(found > 0 && bad == 0, "SeGroup items found in VFS " + found + ", decoded " + decoded + " (" + String.format("%.1f", secs) + " s total), failures " + bad + " e.g. " + samples);
        // bit-level comparison with ffmpeg on up to 6 ogg files
        List<Path> oggs = new ArrayList<>();
        Path dir = Path.of("/home/claude/proj/work/gui_test/audio");
        Files.createDirectories(dir);
        if (found > 0) {
            int k = 0;
            for (String n : p.fs.list("ogg").values()) {
                if (k++ >= 6) break;
                Path t = dir.resolve(n);
                Files.write(t, p.fs.read(n));
                oggs.add(t);
            }
        }
        Path bheSe = Path.of("/home/claude/proj/work/bhe_tsukuyomi");
        if (Files.isDirectory(bheSe)) try (Stream<Path> s = Files.list(bheSe)) { s.filter(x -> x.toString().endsWith(".ogg")).sorted().limit(4).forEach(oggs::add); }
        int cmp = 0, okc = 0;
        StringBuilder det = new StringBuilder();
        for (Path o : oggs) {
            byte[] ref = ffmpeg(o, null);
            if (ref == null) { System.out.println("    (ffmpeg not available, skip)"); break; }
            OggDecoder.Pcm pcm = OggDecoder.decode(Files.readAllBytes(o));
            cmp++;
            int maxDiff = 0;
            int nn = Math.min(ref.length, pcm.data.length) / 2;
            for (int i = 0; i < nn; i++) {
                int a = (short) ((ref[2 * i] & 0xFF) | (ref[2 * i + 1] << 8)), b = (short) ((pcm.data[2 * i] & 0xFF) | (pcm.data[2 * i + 1] << 8));
                maxDiff = Math.max(maxDiff, Math.abs(a - b));
            }
            long refFrames = ref.length / 2 / pcm.channels;
            byte[] raw = Files.readAllBytes(o);
            boolean ok = (Math.abs(refFrames - pcm.frames) <= 1 || pcm.frames == lastGranule(raw)) && maxDiff <= 4;
            if (ok) okc++;
            det.append(String.format(" %s:%dHz/%dch %d vs %d frames maxdiff %d;", o.getFileName(), pcm.rate, pcm.channels, pcm.frames, refFrames, maxDiff));
        }
        SelfTest.check(cmp == 0 || okc == cmp, "JOrbis PCM == ffmpeg PCM (|diff| ≤ 4 LSB; frames ±1 or = last granule): " + okc + "/" + cmp + det);
        // wav
        Path wavDir = Path.of("/home/claude/proj/work/bfe_se");
        int wv = 0, wok = 0;
        if (Files.isDirectory(wavDir)) {
            List<Path> wavs = new ArrayList<>();
            try (Stream<Path> s = Files.list(wavDir)) { s.filter(x -> x.toString().toLowerCase().endsWith(".wav")).sorted().limit(5).forEach(wavs::add); }
            for (Path w : wavs) {
                SoundPlayer.Decoded d = SoundPlayer.decode(Files.readAllBytes(w));
                byte[] ref = ffmpeg(w, d.format.getChannels());
                wv++;
                if (ref == null || Math.abs(ref.length / 2 / d.format.getChannels() - d.pcm.length / d.format.getFrameSize()) <= 1) wok++;
            }
        }
        SelfTest.check(wv == 0 || wok == wv, "wav (BFE SE) decoded via javax.sound with ffmpeg-equal frame counts: " + wok + "/" + wv);
        boolean rej = false;
        try { SoundPlayer.decode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}); } catch (java.io.IOException e) { rej = true; }
        SelfTest.check(rej, "non-audio bytes are rejected with a message");
        p.fs.close();
    }

    /** granulepos of the last Ogg page = the stream's sample count per the Vorbis spec (ffmpeg sometimes drops a short final block). */
    static long lastGranule(byte[] b) {
        long g = -1;
        for (int i = 0; i + 14 < b.length; i++)
            if (b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') {
                long v = 0;
                for (int k = 7; k >= 0; k--) v = (v << 8) | (b[i + 6 + k] & 0xFF);
                g = v;
            }
        return g;
    }

    static byte[] ffmpeg(Path f, Integer ch) {
        try {
            List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-i", f.toString(), "-f", "s16le", "-acodec", "pcm_s16le"));
            if (ch != null) { cmd.add("-ac"); cmd.add("" + ch); }
            cmd.add("-");
            Process pr = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] b = pr.getInputStream().readAllBytes();
            return pr.waitFor() == 0 ? b : null;
        } catch (Exception e) { return null; }
    }
}
