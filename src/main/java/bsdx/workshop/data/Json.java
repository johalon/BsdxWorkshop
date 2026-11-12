package bsdx.workshop.data;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Small helpers for JSON-pointer based editing of NeXAS_DX JSON trees. */
public final class Json {
    private Json() {}
    public static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static String child(String ptr, String key) {
        return ptr + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    public static String idx(String ptr, int i) { return ptr + "/" + i; }

    public static JsonNode at(JsonNode root, String ptr) {
        JsonNode n = root.at(ptr);
        return n.isMissingNode() ? null : n;
    }

    /** Replaces the value at {@code ptr}; the parent container must exist. Returns the previous value (may be null). */
    public static JsonNode set(JsonNode root, String ptr, JsonNode value) {
        JsonPointer p = JsonPointer.compile(ptr);
        JsonNode parent = root.at(p.head());
        String last = p.last().getMatchingProperty();
        if (parent instanceof ObjectNode) {
            ObjectNode o = (ObjectNode) parent;
            JsonNode old = o.get(last);
            if (value == null) o.remove(last); else o.set(last, value);
            return old;
        } else if (parent instanceof ArrayNode) {
            ArrayNode a = (ArrayNode) parent;
            int i = Integer.parseInt(last);
            JsonNode old = a.get(i);
            a.set(i, value);
            return old;
        }
        throw new IllegalArgumentException("no container for " + ptr);
    }

    public static int i(JsonNode n, String key, int def) {
        if (n == null) return def;
        JsonNode v = n.get(key);
        return v == null || !v.isNumber() ? def : v.asInt();
    }

    public static String s(JsonNode n, String key, String def) {
        if (n == null) return def;
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? def : v.asText();
    }

    public static IntNode num(int v) { return IntNode.valueOf(v); }

    /** Signed-byte JSON array (hutool's byte[] form) -> little-endian int32 values. */
    public static int[] bytesToInts(JsonNode arr, int n) {
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            int v = 0;
            for (int b = 0; b < 4; b++) {
                int idx = k * 4 + b;
                int x = arr != null && idx < arr.size() ? arr.get(idx).asInt() & 0xFF : 0;
                v |= x << (8 * b);
            }
            out[k] = v;
        }
        return out;
    }

    public static ArrayNode intsToBytes(int[] vals) {
        ArrayNode a = F.arrayNode();
        for (int v : vals) for (int b = 0; b < 4; b++) a.add((int) (byte) (v >> (8 * b)));
        return a;
    }

    /** Keys the generator recomputes (file offsets, block sizes / sequence offsets of mek, material entry lengths). */
    public static boolean isBookkeeping(String k, JsonNode obj) {
        return k.equals("offset") || k.equals("mekHead") || k.equals("mekBlocks") || (k.equals("length") && obj.has("spriteGroups"));
    }

    /** Deep equality that ignores parser bookkeeping keys (see {@link #isBookkeeping}). */
    public static boolean sameIgnoringOffsets(JsonNode a, JsonNode b, List<String> diffs, String path, int maxDiffs) {
        if (diffs.size() >= maxDiffs) return false;
        if (a == null || b == null) {
            if (a == b) return true;
            diffs.add(path + ": " + a + " != " + b);
            return false;
        }
        if (a.isObject() && b.isObject()) {
            boolean ok = true;
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (isBookkeeping(k, a)) continue;
                ok &= sameIgnoringOffsets(a.get(k), b.get(k), diffs, path + "/" + k, maxDiffs);
            }
            return ok;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) { diffs.add(path + ": size " + a.size() + " != " + b.size()); return false; }
            boolean ok = true;
            for (int k = 0; k < a.size(); k++) ok &= sameIgnoringOffsets(a.get(k), b.get(k), diffs, path + "/" + k, maxDiffs);
            return ok;
        }
        if (a.isNumber() && b.isNumber()) {
            if (a.asLong() == b.asLong() && a.asDouble() == b.asDouble()) return true;
        } else if (a.equals(b)) return true;
        diffs.add(path + ": " + a + " != " + b);
        return false;
    }

    /** Lists leaf differences between two trees (ignores offsets). */
    public static List<String> diff(JsonNode a, JsonNode b, int max) {
        List<String> d = new ArrayList<>();
        sameIgnoringOffsets(a, b, d, "", max);
        return d;
    }

    /** Finds the element of a sub-object list (ceventHitUnitList etc.) with unitSlotNum == idx; returns its index or -1. */
    public static int subIndex(JsonNode list, int unitSlotNum) {
        if (list == null || !list.isArray()) return -1;
        for (int k = 0; k < list.size(); k++) {
            JsonNode u = list.get(k);
            if (u.path("unitSlotNum").asInt(-999) == unitSlotNum && u.path("buffer").asInt(0) != 0 && u.has("data") && !u.get("data").isNull())
                return k;
        }
        return -1;
    }

    /** Name of the first key whose value is a sub-object list (has unitSlotNum entries), or null. */
    public static String subListKey(JsonNode obj) {
        if (obj == null || !obj.isObject()) return null;
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isArray() && v.size() > 0 && v.get(0).isObject() && v.get(0).has("unitSlotNum")) return e.getKey();
            if (v.isArray() && e.getKey().endsWith("UnitList")) return e.getKey();
        }
        return null;
    }
}
