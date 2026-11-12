package bsdx.workshop.pac;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.Deflater;

/**
 * Writes a type-4 NeXAS PAC exactly like tools/nexaspac.py write_pac(): entries zlib level 6 unless the
 * extension is ogg/wav/png/fnt/mpg/mpeg/avi (stored). Never falls back to "stored" just because zlib is larger
 * (the exe decides by extension). Index = Huffman + NOT, size u32 at EOF.
 * The caller is responsible for choosing a SAFE destination (see ExportGuard).
 */
public final class PacWriter {
    private PacWriter() {}

    public static final class Item {
        public final String name;
        public final byte[] data;
        public Item(String name, byte[] data) { this.name = name; this.data = data; }
    }

    public static byte[] zlib(byte[] data, int level) {
        Deflater d = new Deflater(level);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[65536];
            while (!d.finished()) {
                int n = d.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** Writes to a temp file next to {@code dest} and atomically moves it into place. */
    public static void write(Path dest, List<Item> items) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Item it : items) {
            byte[] bn = it.name.getBytes(PacArchive.CP932);
            if (bn.length >= PacArchive.NAME_LEN) throw new IOException("file name too long (>=64 bytes cp932): " + it.name);
            if (it.name.contains("/") || it.name.contains("\\")) throw new IOException("directories are not allowed in pac names: " + it.name);
            if (!seen.add(it.name.toLowerCase(Locale.ROOT))) throw new IOException("duplicate name (case-insensitive): " + it.name);
        }
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        ByteArrayOutputStream index = new ByteArrayOutputStream(items.size() * PacArchive.ENTRY_SIZE);
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            os.write(new byte[]{'P', 'A', 'C', 0});
            os.write(le32(items.size()));
            os.write(le32(4));
            long pos = 0x0C;
            for (Item it : items) {
                byte[] blob = PacArchive.TYPE4_STORED_EXT.contains(PacArchive.extOf(it.name)) ? it.data : zlib(it.data, 6);
                byte[] name = Arrays.copyOf(it.name.getBytes(PacArchive.CP932), PacArchive.NAME_LEN);
                index.write(name);
                index.write(le32((int) pos));
                index.write(le32(it.data.length));
                index.write(le32(blob.length));
                os.write(blob);
                pos += blob.length;
                if (pos > 0xFFFFFFFFL) throw new IOException("pac larger than 4GB");
            }
            byte[] enc = Huffman.encode(index.toByteArray());
            for (int i = 0; i < enc.length; i++) enc[i] = (byte) ~enc[i];
            os.write(enc);
            os.write(le32(enc.length));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
