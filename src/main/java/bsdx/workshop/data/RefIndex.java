package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.giga.nexas.dto.bsdx.BsdxInfoCollection;
import com.giga.nexas.dto.bsdx.waz.Waz;
import com.giga.nexas.dto.bsdx.waz.wazfactory.wazinfoclass.obj.CEventWazaSelect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Who references which waz skill: WazaSelect (bullet / effect spawn, hit effect) and Term "技ジャンプ"
 * (JUMP group 16 item 1, paramList = wazFileNo, skillNo). Built from every registered waz so the GUI can warn
 * when a shared bullet is used by other mechs.
 */
public final class RefIndex {
    public static final class Ref {
        public final int srcWaz, srcSkill; public final String kind;
        Ref(int srcWaz, int srcSkill, String kind) { this.srcWaz = srcWaz; this.srcSkill = srcSkill; this.kind = kind; }
    }

    private final Project p;
    private final Map<Long, List<Ref>> refs = new HashMap<>();
    public int wazScanned, refCount;
    public long millis;
    public final List<String> errors = new ArrayList<>();

    RefIndex(Project p) { this.p = p; }

    static long key(int waz, int skill) { return ((long) waz << 32) | (skill & 0xFFFFFFFFL); }

    public synchronized List<Ref> referrers(int waz, int skill) {
        return new ArrayList<>(refs.getOrDefault(key(waz, skill), List.of()));
    }

    void build() {
        long t0 = System.currentTimeMillis();
        int n = p.wazaList().size();
        for (int w = 0; w < n; w++) {
            String f = p.wazFile(w);
            if (f == null) continue;
            try {
                FileDoc d = p.docIfLoaded(f);
                if (d != null) { scanTree(w, d.tree); }
                else {
                    byte[] b = p.fs.read(f);
                    if (b == null) continue;
                    Waz waz = (Waz) p.codec.parse(f, b);
                    List<Waz.Skill> sl = waz.getSkillList();
                    for (int s = 0; s < sl.size(); s++) walkDto(sl.get(s), w, s, Collections.newSetFromMap(new IdentityHashMap<>()));
                }
                wazScanned++;
            } catch (Exception e) {
                errors.add(f + ": " + e);
            }
        }
        millis = System.currentTimeMillis() - t0;
    }

    private synchronized void add(int target, int seq, int srcWaz, int srcSkill, String kind) {
        if (target == -1) target = srcWaz;
        refs.computeIfAbsent(key(target, seq), k -> new ArrayList<>()).add(new Ref(srcWaz, srcSkill, kind));
        refCount++;
    }

    /** Re-scans an (edited) waz tree, replacing its previous references. */
    public synchronized void update(int wazIdx, JsonNode tree) {
        for (List<Ref> l : refs.values()) l.removeIf(r -> r.srcWaz == wazIdx);
        scanTree(wazIdx, tree);
    }

    private void scanTree(int w, JsonNode tree) {
        JsonNode sl = tree.path("skillList");
        for (int s = 0; s < sl.size(); s++) walkJson(sl.get(s), w, s);
    }

    private void walkJson(JsonNode n, int w, int s) {
        if (n == null) return;
        if (n.isObject()) {
            if (n.has("wazFileNo") && n.has("wazSequenceNo")) add(n.get("wazFileNo").asInt(), n.get("wazSequenceNo").asInt(), w, s, "WazaSelect");
            if (n.path("int1").asInt(-1) == 16 && n.has("typeList") && n.get("typeList").size() > 0 && n.get("typeList").get(0).asInt() == 1
                    && n.path("paramList").size() >= 2)
                add(n.get("paramList").get(0).asInt(), n.get("paramList").get(1).asInt(), w, s, "技ジャンプ");
            n.elements().forEachRemaining(c -> { if (c.isContainerNode()) walkJson(c, w, s); });
        } else if (n.isArray()) {
            for (JsonNode c : n) if (c.isContainerNode()) walkJson(c, w, s);
        }
    }

    private static final Map<Class<?>, List<Field>> FIELDS = new HashMap<>();

    private static List<Field> fields(Class<?> c) {
        synchronized (FIELDS) {
            List<Field> l = FIELDS.get(c);
            if (l != null) return l;
            l = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class
                            || Number.class.isAssignableFrom(f.getType()) || f.getType().isArray()) continue;
                    f.setAccessible(true);
                    l.add(f);
                }
            FIELDS.put(c, l);
            return l;
        }
    }

    private void walkDto(Object o, int w, int s, Set<Object> seen) {
        if (o == null) return;
        if (o instanceof List) { for (Object x : (List<?>) o) walkDto(x, w, s, seen); return; }
        Class<?> c = o.getClass();
        if (!c.getName().startsWith("com.giga.nexas")) return;
        if (!seen.add(o)) return;
        if (o instanceof CEventWazaSelect) {
            CEventWazaSelect ws = (CEventWazaSelect) o;
            if (ws.getWazFileNo() != null && ws.getWazSequenceNo() != null) add(ws.getWazFileNo(), ws.getWazSequenceNo(), w, s, "WazaSelect");
        } else if (o instanceof BsdxInfoCollection) {
            BsdxInfoCollection t = (BsdxInfoCollection) o;
            if (t.getInt1() != null && t.getInt1() == 16 && t.getTypeList() != null && !t.getTypeList().isEmpty() && t.getTypeList().get(0) == 1
                    && t.getParamList() != null && t.getParamList().size() >= 2)
                add(t.getParamList().get(0), t.getParamList().get(1), w, s, "技ジャンプ");
            return;
        }
        for (Field f : fields(c)) {
            try { walkDto(f.get(o), w, s, seen); } catch (IllegalAccessException ignored) {}
        }
    }
}
