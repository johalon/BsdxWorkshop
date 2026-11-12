package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Human readable diff of two NeXAS_DX JSON trees ("diff vs original"). Arrays of objects are aligned with an LCS on
 * their content (bookkeeping keys ignored), so an inserted skill / turn / event / weapon shows up as one "+" line
 * instead of hundreds of shifted values.
 */
public final class TreeDiff {
    public static final class Line {
        public final char op;  // '~' changed, '+' added, '-' removed
        public final String path, text;
        Line(char op, String path, String text) { this.op = op; this.path = path; this.text = text; }
        @Override public String toString() { return op + " " + path + (text.isEmpty() ? "" : "  " + text); }
    }

    private final List<Line> out = new ArrayList<>();
    private final int max;
    private final Map<JsonNode, Long> keyCache = new IdentityHashMap<>();

    private TreeDiff(int max) { this.max = max; }

    public static List<Line> diff(JsonNode a, JsonNode b, int max) {
        TreeDiff d = new TreeDiff(max);
        d.walk(a, b, "");
        return d.out;
    }

    private boolean full() { return out.size() >= max; }

    private long key(JsonNode n) {
        Long k = keyCache.get(n);
        if (k == null) { k = hash(n); keyCache.put(n, k); }
        return k;
    }

    /** 64-bit content hash ignoring bookkeeping keys (cached per node identity). */
    private long hash(JsonNode n) {
        if (n == null) return 0x9E3779B97F4A7C15L;
        long h;
        if (n.isObject()) {
            h = 0x1234567L;
            TreeMap<String, JsonNode> m = new TreeMap<>();
            n.fields().forEachRemaining(e -> { if (!Json.isBookkeeping(e.getKey(), n)) m.put(e.getKey(), e.getValue()); });
            for (Map.Entry<String, JsonNode> e : m.entrySet()) h = mix(mix(h, e.getKey().hashCode()), key(e.getValue()));
        } else if (n.isArray()) {
            h = 0x7654321L;
            for (JsonNode c : n) h = mix(h, key(c));
        } else h = mix(0x51L, n.isNumber() ? n.asLong() : n.asText().hashCode() * 0x100000001L + 7);
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 31);
    }

    static String label(JsonNode n) {
        if (n == null) return "";
        if (n.isObject()) {
            for (String k : new String[]{"skillNameJapanese", "weaponName", "aiTypeJapanese", "unitDescription"}) if (n.has(k)) return "「" + n.get(k).asText() + "」";
            if (n.has("startFrame")) return "frame " + n.path("startFrame").asInt() + "-" + n.path("endFrame").asInt() + (n.has("typeId") ? " type " + n.get("typeId").asInt() : "");
            if (n.has("type") && n.has("activationProbability")) return n.get("type").asInt() == 2 ? "AI 攻撃 武装#" + n.path("mekWeaponInfoMapNo").asInt() : "AI 移動";
            if (n.has("skillUnitCollection")) return "ターン（スロット " + n.get("skillUnitCollection").size() + "）";
            return "{" + n.size() + " 項目}";
        }
        String s = n.toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private void walk(JsonNode a, JsonNode b, String path) {
        if (full()) return;
        if (a == null || b == null) {
            if (a == null && b == null) return;
            out.add(new Line(a == null ? '+' : '-', path, label(a == null ? b : a)));
            return;
        }
        if (a.isObject() && b.isObject()) {
            if (a.has("spriteGroups") && b.has("spriteGroups") && key(a) == key(b)) return;
            Set<String> keys = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (Json.isBookkeeping(k, a.has(k) ? a : b)) continue;
                walk(a.get(k), b.get(k), Json.child(path, k));
            }
            return;
        }
        if (a.isArray() && b.isArray()) {
            boolean objs = (a.size() > 0 && a.get(0).isContainerNode()) || (b.size() > 0 && b.get(0).isContainerNode());
            if (objs && a.size() == b.size() && flat(a) && flat(b)) { for (int i = 0; i < a.size(); i++) walk(a.get(i), b.get(i), path + "/" + i); return; }
            if (!objs) {
                if (a.size() == b.size()) { for (int i = 0; i < a.size(); i++) walk(a.get(i), b.get(i), path + "/" + i); }
                else out.add(new Line('~', path, label(a) + " → " + label(b)));
                return;
            }
            lcs(a, b, path);
            return;
        }
        boolean same = a.isNumber() && b.isNumber() ? a.asLong() == b.asLong() && a.asDouble() == b.asDouble() : a.equals(b);
        if (!same) out.add(new Line('~', path, label(a) + " → " + label(b)));
    }

    /** Array of small records whose members are all scalars (pairs, Term-less rows): compared position by position. */
    static boolean flat(JsonNode arr) {
        for (JsonNode e : arr) {
            if (!e.isObject()) return false;
            for (JsonNode c : e) if (c.isContainerNode()) return false;
        }
        return true;
    }

    private void lcs(JsonNode a, JsonNode b, String path) {
        int n = a.size(), m = b.size();
        if ((long) n * m > 4_000_000L) { out.add(new Line('~', path, "要素数 " + n + " → " + m + "（大きすぎるため詳細省略）")); return; }
        long[] ka = new long[n], kb = new long[m];
        for (int i = 0; i < n; i++) ka[i] = key(a.get(i));
        for (int j = 0; j < m; j++) kb[j] = key(b.get(j));
        int[][] L = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) L[i][j] = ka[i] == kb[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
        int i = 0, j = 0;
        List<Integer> ra = new ArrayList<>(), rb = new ArrayList<>();
        while (i < n || j < m) {
            if (i < n && j < m && ka[i] == kb[j]) { flush(a, b, ra, rb, path); i++; j++; continue; }
            if (j < m && (i == n || L[i][j + 1] >= L[i + 1][j])) { rb.add(j++); } else { ra.add(i++); }
        }
        flush(a, b, ra, rb, path);
    }

    /** A run of unmatched elements: pair them up (modified) when both sides have them, the rest are added / removed. */
    private void flush(JsonNode a, JsonNode b, List<Integer> ra, List<Integer> rb, String path) {
        int k = Math.min(ra.size(), rb.size());
        for (int x = 0; x < k; x++) walk(a.get(ra.get(x)), b.get(rb.get(x)), path + "/" + rb.get(x));
        for (int x = k; x < ra.size() && !full(); x++) out.add(new Line('-', path + "/" + ra.get(x), "（元の " + ra.get(x) + " 番）" + label(a.get(ra.get(x)))));
        for (int x = k; x < rb.size() && !full(); x++) out.add(new Line('+', path + "/" + rb.get(x), label(b.get(rb.get(x)))));
        ra.clear(); rb.clear();
    }
}
