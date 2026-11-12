package bsdx.workshop.pac;

import java.io.ByteArrayOutputStream;
import java.util.PriorityQueue;

/**
 * NeXAS / GARbro HuffmanDecompressor: MSB-first bit stream, pre-order tree (1 = internal node {left,right},
 * 0 = 8-bit leaf), internal nodes numbered from 256. Port of tools/nexaspac.py huffman_decode/huffman_encode.
 */
public final class Huffman {
    private Huffman() {}

    private static final class BitReader {
        final byte[] d; int pos, bit, cur;
        BitReader(byte[] d) { this.d = d; }
        int get(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (bit == 0) {
                    if (pos >= d.length) return -1;
                    cur = d[pos++] & 0xFF;
                    bit = 8;
                }
                bit--;
                v = (v << 1) | ((cur >> bit) & 1);
            }
            return v;
        }
    }

    public static byte[] decode(byte[] src, int outSize) {
        BitReader br = new BitReader(src);
        int[] lhs = new int[512], rhs = new int[512];
        int[] token = {256};
        int root = tree(br, lhs, rhs, token);
        byte[] out = new byte[outSize];
        int n = 0;
        while (n < outSize) {
            int s = root;
            while (s >= 0x100) {
                int b = br.get(1);
                if (b == -1) { byte[] t = new byte[n]; System.arraycopy(out, 0, t, 0, n); return t; }
                s = b != 0 ? rhs[s] : lhs[s];
            }
            out[n++] = (byte) s;
        }
        return out;
    }

    private static int tree(BitReader br, int[] lhs, int[] rhs, int[] token) {
        int b = br.get(1);
        if (b == -1) throw new IllegalStateException("huffman: eof in tree");
        if (b != 0) {
            int v = token[0]++;
            if (v >= 512) throw new IllegalStateException("huffman: bad tree");
            lhs[v] = tree(br, lhs, rhs, token);
            rhs[v] = tree(br, lhs, rhs, token);
            return v;
        }
        return br.get(8);
    }

    private static final class BitWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cur, nbits;
        void put(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                cur = (cur << 1) | ((value >> i) & 1);
                if (++nbits == 8) { out.write(cur); cur = 0; nbits = 0; }
            }
        }
        void putCode(long code, int len) {
            for (int i = len - 1; i >= 0; i--) {
                cur = (cur << 1) | (int) ((code >> i) & 1);
                if (++nbits == 8) { out.write(cur); cur = 0; nbits = 0; }
            }
        }
        byte[] finish() {
            if (nbits > 0) { out.write(cur << (8 - nbits)); cur = 0; nbits = 0; }
            return out.toByteArray();
        }
    }

    /** Inverse of {@link #decode}. Deterministic (ties broken by node id like the Python version). */
    public static byte[] encode(byte[] data) {
        long[] freq = new long[256];
        for (byte b : data) freq[b & 0xFF]++;
        // node: [freq, tiebreak, id]
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0])
                : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        for (int i = 0; i < 256; i++) if (freq[i] > 0) pq.add(new long[]{freq[i], i, i});
        if (pq.isEmpty()) pq.add(new long[]{1, 0, 0});
        if (pq.size() == 1) {
            long only = pq.peek()[2];
            long other = only != 1 ? 1 : 2;
            pq.add(new long[]{0, other, other});
        }
        int[][] children = new int[512][];
        int next = 256;
        while (pq.size() > 1) {
            long[] a = pq.poll(), b = pq.poll();
            children[next] = new int[]{(int) a[2], (int) b[2]};
            pq.add(new long[]{a[0] + b[0], next, next});
            next++;
        }
        int root = (int) pq.peek()[2];
        long[] codes = new long[256];
        int[] lens = new int[256];
        BitWriter bw = new BitWriter();
        walk(root, 0L, 0, children, codes, lens, bw);
        for (byte b : data) {
            int v = b & 0xFF;
            if (lens[v] > 62) throw new IllegalStateException("huffman code too long");
            bw.putCode(codes[v], lens[v]);
        }
        return bw.finish();
    }

    private static void walk(int n, long code, int len, int[][] ch, long[] codes, int[] lens, BitWriter bw) {
        if (n >= 256) {
            bw.put(1, 1);
            walk(ch[n][0], code << 1, len + 1, ch, codes, lens, bw);
            walk(ch[n][1], (code << 1) | 1, len + 1, ch, codes, lens, bw);
        } else {
            bw.put(0, 1);
            bw.put(n, 8);
            codes[n] = code;
            lens[n] = len;
        }
    }
}
