package bsdx.workshop.audio;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Ogg Vorbis -> 16-bit little-endian PCM, entirely in memory, using the vendored pure-Java JOrbis
 * (third_party/jorbis-src, LGPL). Handles chained streams (all links must share rate/channels).
 * The output is trimmed to the last page's granule position (what libvorbisfile does).
 */
public final class OggDecoder {
    private OggDecoder() {}

    public static final class Pcm {
        public final byte[] data; public final int rate, channels;
        public final long frames;
        Pcm(byte[] data, int rate, int channels) { this.data = data; this.rate = rate; this.channels = channels; this.frames = data.length / (2L * channels); }
        public double seconds() { return rate == 0 ? 0 : (double) frames / rate; }
    }

    public static boolean isOgg(byte[] b) { return b != null && b.length > 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S'; }

    public static Pcm decode(byte[] in) throws IOException {
        if (!isOgg(in)) throw new IOException("Ogg ではありません");
        SyncState oy = new SyncState();
        oy.init();
        int pos = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 10);
        int rate = 0, channels = 0;
        long granuleTotal = 0, linkBase = 0;   // frames written before the current link
        while (true) {   // one iteration per chained link
            StreamState os = new StreamState();
            Page og = new Page();
            Packet op = new Packet();
            Info vi = new Info();
            Comment vc = new Comment();
            DspState vd = new DspState();
            Block vb = new Block(vd);
            // first page
            int r;
            while ((r = oy.pageout(og)) != 1) {
                if (pos >= in.length) {
                    if (rate == 0) throw new IOException("Vorbis ヘッダが見つかりません");
                    return finish(out, rate, channels, granuleTotal);
                }
                pos = feed(oy, in, pos);
            }
            os.init(og.serialno());
            vi.init();
            vc.init();
            if (os.pagein(og) < 0 || os.packetout(op) != 1 || vi.synthesis_headerin(vc, op) < 0) throw new IOException("Vorbis ストリームではありません");
            int got = 0;
            while (got < 2) {
                r = oy.pageout(og);
                if (r == 0) {
                    if (pos >= in.length) throw new IOException("Vorbis ヘッダの途中でファイルが終わっています");
                    pos = feed(oy, in, pos);
                    continue;
                }
                if (r < 0) continue;
                os.pagein(og);
                while (got < 2) {
                    int pr = os.packetout(op);
                    if (pr == 0) break;
                    if (pr < 0) throw new IOException("Vorbis ヘッダが壊れています");
                    vi.synthesis_headerin(vc, op);
                    got++;
                }
            }
            if (rate != 0 && (rate != vi.rate || channels != vi.channels)) throw new IOException("連結ストリームの形式が途中で変わります");
            rate = vi.rate;
            channels = vi.channels;
            vd.synthesis_init(vi);
            vb.init(vd);
            float[][][] pcmHolder = new float[1][][];
            int[] index = new int[channels];
            long linkFrames = 0, lastGranule = -1;
            boolean eos = false;
            byte[] conv = new byte[8192 * channels];
            while (!eos) {
                r = oy.pageout(og);
                if (r == 0) {
                    if (pos >= in.length) break;
                    pos = feed(oy, in, pos);
                    continue;
                }
                if (r < 0) continue;   // hole in data
                os.pagein(og);
                if (og.granulepos() >= 0) lastGranule = og.granulepos();
                while (true) {
                    int pr = os.packetout(op);
                    if (pr == 0) break;
                    if (pr < 0) continue;
                    if (vb.synthesis(op) == 0) vd.synthesis_blockin(vb);
                    int samples;
                    while ((samples = vd.synthesis_pcmout(pcmHolder, index)) > 0) {
                        float[][] pcm = pcmHolder[0];
                        int n = Math.min(samples, 8192);
                        int need = n * 2 * channels;
                        if (conv.length < need) conv = new byte[need];
                        for (int c = 0; c < channels; c++) {
                            int p = c * 2;
                            int base = index[c];
                            for (int j = 0; j < n; j++) {
                                int v = (int) (pcm[c][base + j] * 32767.0f);
                                if (v > 32767) v = 32767;
                                if (v < -32768) v = -32768;
                                conv[p] = (byte) v;
                                conv[p + 1] = (byte) (v >> 8);
                                p += 2 * channels;
                            }
                        }
                        out.write(conv, 0, need);
                        linkFrames += n;
                        vd.synthesis_read(n);
                    }
                }
                if (og.eos() != 0) eos = true;
            }
            granuleTotal = linkBase + (lastGranule >= 0 ? Math.min(lastGranule, linkFrames) : linkFrames);
            // drop the excess decoded past the granule of this link
            long keep = granuleTotal * 2L * channels;
            if (out.size() > keep) {
                byte[] all = out.toByteArray();
                out.reset();
                out.write(all, 0, (int) keep);
            }
            os.clear();
            vb.clear();
            vd.clear();
            vi.clear();
            return finish(out, rate, channels, granuleTotal);   // chained links (not used by the game) are ignored
        }
    }

    private static Pcm finish(ByteArrayOutputStream out, int rate, int channels, long frames) {
        byte[] d = out.toByteArray();
        return new Pcm(d, rate, Math.max(1, channels));
    }

    private static int feed(SyncState oy, byte[] in, int pos) {
        int n = Math.min(4096, in.length - pos);
        int idx = oy.buffer(n);
        System.arraycopy(in, pos, oy.data, idx, n);
        oy.wrote(n);
        return pos + n;
    }
}
