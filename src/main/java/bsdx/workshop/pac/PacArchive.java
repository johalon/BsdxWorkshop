package bsdx.workshop.pac;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * READ-ONLY reader for NeXAS "new" PAC archives (BSDX). Port of tools/nexaspac.py.
 * <pre>
 * 0x00 "PAC" + garbage byte, 0x04 u32 count, 0x08 u32 pack type (BSDX: always 4), 0x0C entry data...
 * EOF-4-N: index, Huffman coded then every byte NOT-ed; EOF-4: u32 N.
 * decoded index = count * 0x4C: char name[0x40] (cp932), u32 offset, u32 unpacked, u32 packed
 * type 4: method decided by EXTENSION (BaldrSky.exe 0x794150): ogg/wav/png/fnt/mpg/mpeg/avi stored, rest zlib.
 * </pre>
 * The file is opened with mode "r" only and never written. Large pacs (Bmp.pac 2.1GB) are fine: only the index
 * and requested entries are read.
 */
public final class PacArchive implements Closeable {
    public static final Charset CP932 = Charset.forName("windows-31j");
    public static final int NAME_LEN = 0x40, ENTRY_SIZE = 0x4C;
    public static final Set<String> TYPE4_STORED_EXT = Set.of("ogg", "wav", "png", "fnt", "mpg", "mpeg", "avi");

    public static final class Entry {
        public final String name;
        public final long offset, usize, psize;
        Entry(String name, long offset, long usize, long psize) {
            this.name = name; this.offset = offset; this.usize = usize; this.psize = psize;
        }
        @Override public String toString() { return name + " @" + offset + " " + psize + "/" + usize; }
    }

    private final Path path;
    private final RandomAccessFile raf;
    private final int count, packType, byte3;
    private final List<Entry> entries;
    private final Map<String, Entry> byLower = new LinkedHashMap<>();

    public PacArchive(Path path) throws IOException {
        this.path = path;
        this.raf = new RandomAccessFile(path.toFile(), "r");
        try {
            byte[] head = new byte[12];
            raf.readFully(head);
            if (head[0] != 'P' || head[1] != 'A' || head[2] != 'C') throw new IOException("not a PAC: " + path);
            byte3 = head[3] & 0xFF;
            count = le32(head, 4);
            packType = le32(head, 8);
            long len = raf.length();
            raf.seek(len - 4);
            byte[] b4 = new byte[4];
            raf.readFully(b4);
            long isz = le32(b4, 0) & 0xFFFFFFFFL;
            if (isz <= 0 || isz > len - 16) throw new IOException("bad index size " + isz + " in " + path);
            byte[] enc = new byte[(int) isz];
            raf.seek(len - 4 - isz);
            raf.readFully(enc);
            for (int i = 0; i < enc.length; i++) enc[i] = (byte) ~enc[i];
            byte[] raw = Huffman.decode(enc, count * ENTRY_SIZE);
            if (raw.length != count * ENTRY_SIZE) throw new IOException("index decode short in " + path);
            List<Entry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int o = i * ENTRY_SIZE;
                int n = 0;
                while (n < NAME_LEN && raw[o + n] != 0) n++;
                String name = new String(raw, o, n, CP932);
                long off = le32(raw, o + NAME_LEN) & 0xFFFFFFFFL;
                long us = le32(raw, o + NAME_LEN + 4) & 0xFFFFFFFFL;
                long ps = le32(raw, o + NAME_LEN + 8) & 0xFFFFFFFFL;
                Entry e = new Entry(name, off, us, ps);
                list.add(e);
                byLower.putIfAbsent(name.toLowerCase(Locale.ROOT), e);
            }
            entries = Collections.unmodifiableList(list);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    public static String extOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    public Path getPath() { return path; }
    public int getPackType() { return packType; }
    public int getByte3() { return byte3; }
    public List<Entry> entries() { return entries; }
    public Entry find(String name) { return byLower.get(name.toLowerCase(Locale.ROOT)); }

    /** Mirrors the exe: for pack type 4 the method is chosen by the extension, not by size. */
    public boolean isPacked(Entry e) {
        if (packType == 0 || packType == 5) return false;
        if (packType == 4) return !TYPE4_STORED_EXT.contains(extOf(e.name));
        return true;
    }

    public synchronized byte[] read(Entry e) throws IOException {
        if (e.psize > Integer.MAX_VALUE - 16 || e.usize > Integer.MAX_VALUE - 16) throw new IOException("entry too large: " + e);
        byte[] raw = new byte[(int) e.psize];
        raf.seek(e.offset);
        raf.readFully(raw);
        if (!isPacked(e)) return raw;
        switch (packType) {
            case 1: return Lzss.decode(raw, (int) e.usize);
            case 2: return Huffman.decode(raw, (int) e.usize);
            case 3: case 4: return inflate(raw, (int) e.usize, e.name);
            default: throw new IOException("unsupported pack type " + packType);
        }
    }

    public byte[] read(String name) throws IOException {
        Entry e = find(name);
        return e == null ? null : read(e);
    }

    static byte[] inflate(byte[] raw, int usize, String name) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(usize, 64));
            byte[] buf = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IOException("truncated zlib stream: " + name);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            throw new IOException("zlib error in " + name + ": " + ex.getMessage(), ex);
        } finally {
            inf.end();
        }
    }

    @Override public void close() throws IOException { raf.close(); }

    /** Classic Okumura LZSS (type 1; not used by BSDX, kept for completeness). */
    static final class Lzss {
        static byte[] decode(byte[] src, int outSize) {
            byte[] win = new byte[0x1000];
            int wp = 0xFEE, i = 0, n = src.length;
            ByteArrayOutputStream out = new ByteArrayOutputStream(outSize);
            while (i < n && out.size() < outSize) {
                int ctl = src[i++] & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    if (i >= n || out.size() >= outSize) break;
                    if ((ctl & (1 << bit)) != 0) {
                        byte c = src[i++];
                        out.write(c); win[wp] = c; wp = (wp + 1) & 0xFFF;
                    } else {
                        if (i + 1 >= n) break;
                        int lo = src[i] & 0xFF, hi = src[i + 1] & 0xFF; i += 2;
                        int off = lo | ((hi & 0xF0) << 4), cnt = (hi & 0x0F) + 3;
                        for (int k = 0; k < cnt; k++) {
                            byte c = win[(off + k) & 0xFFF];
                            out.write(c); win[wp] = c; wp = (wp + 1) & 0xFFF;
                        }
                    }
                }
            }
            return out.toByteArray();
        }
    }
}
