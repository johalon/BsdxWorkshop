package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One editable game file (mek / waz) held as a NeXAS_DX JSON tree. */
public final class FileDoc {
    public final String name;          // original-case file name, e.g. "Kou.waz"
    public final String lower;
    public final String ext;
    public final String sourceLabel;   // layer the file came from (Update.pac, overlay ...)
    public final byte[] originalBytes;
    public final ObjectNode tree;
    /** pointer -> value before the first edit of that pointer (to show "changed" marks and the change log). */
    final Map<String, JsonNode> originals = new LinkedHashMap<>();
    int editDepth;                     // number of applied (not undone) edits touching this doc
    /** Incremented on every applied / undone change (cache key). */
    public int version;
    private int cacheVer = -1;
    private Map<String, String> cachedChanges;

    public FileDoc(String name, String sourceLabel, byte[] originalBytes, ObjectNode tree) {
        this.name = name;
        this.lower = name.toLowerCase(Locale.ROOT);
        this.ext = Codec.ext(name);
        this.sourceLabel = sourceLabel;
        this.originalBytes = originalBytes;
        this.tree = tree;
    }

    /** True if some value differs from the loaded file. */
    public boolean isDirty() { return !changes().isEmpty(); }

    public boolean isChanged(String ptr) {
        if (!originals.containsKey(ptr)) return false;
        return !Objects.equals(originals.get(ptr), Json.at(tree, ptr));
    }

    public JsonNode originalValue(String ptr) {
        return originals.containsKey(ptr) ? originals.get(ptr) : Json.at(tree, ptr);
    }

    /** "ptr: old -> new" lines for every changed value. */
    public synchronized Map<String, String> changes() {
        if (cacheVer == version && cachedChanges != null) return cachedChanges;
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : originals.entrySet()) {
            JsonNode now = Json.at(tree, e.getKey());
            if (!Objects.equals(e.getValue(), now)) m.put(e.getKey(), brief(e.getValue()) + " -> " + brief(now));
        }
        cachedChanges = java.util.Collections.unmodifiableMap(m);
        cacheVer = version;
        return cachedChanges;
    }

    /** Short text of a value; big containers (structural edits) are summarised. */
    static String brief(JsonNode n) {
        if (n == null) return "null";
        if (n.isArray() && n.size() > 0 && n.get(0).isContainerNode()) return "[" + n.size() + " 要素]";
        if (n.isObject() && n.size() > 0 && n.elements().next().isContainerNode()) return "{" + n.size() + " 項目}";
        String s = n.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @Override public String toString() { return name; }
}
