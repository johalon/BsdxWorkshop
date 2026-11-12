package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Navigation helpers over a waz JSON tree (skill -> turn -> slot -> event). */
public final class WazNav {
    private WazNav() {}

    public static final class Ev {
        public final int skill, turn, unitIdx, slot, objIdx;
        public final String ptr;
        public final JsonNode node;
        Ev(int skill, int turn, int unitIdx, int slot, int objIdx, String ptr, JsonNode node) {
            this.skill = skill; this.turn = turn; this.unitIdx = unitIdx; this.slot = slot; this.objIdx = objIdx; this.ptr = ptr; this.node = node;
        }
        public int typeId() { return node.path("typeId").asInt(-1); }
        public int start() { return node.path("startFrame").asInt(); }
        public int end() { return node.path("endFrame").asInt(); }
    }

    public static String skillPtr(int si) { return "/skillList/" + si; }

    public static JsonNode skill(JsonNode waz, int si) {
        JsonNode sl = waz.path("skillList");
        return si >= 0 && si < sl.size() ? sl.get(si) : null;
    }

    public static boolean isEmptySkill(JsonNode s) {
        return s == null || !s.isObject() || !s.has("skillNameJapanese") && s.path("phasesInfo").size() == 0;
    }

    public static String skillName(JsonNode waz, int si) {
        JsonNode s = skill(waz, si);
        if (s == null) return "(範囲外)";
        if (isEmptySkill(s)) return "(空)";
        String n = Json.s(s, "skillNameJapanese", "");
        return n.isEmpty() ? "(無名)" : n;
    }

    public static List<Ev> events(JsonNode waz, int si, int turnFilter) {
        List<Ev> out = new ArrayList<>();
        JsonNode s = skill(waz, si);
        if (s == null) return out;
        JsonNode ph = s.path("phasesInfo");
        for (int t = 0; t < ph.size(); t++) {
            if (turnFilter >= 0 && t != turnFilter) continue;
            JsonNode units = ph.get(t).path("skillUnitCollection");
            for (int u = 0; u < units.size(); u++) {
                JsonNode unit = units.get(u);
                int slot = unit.path("unitQuantity").asInt();
                JsonNode objs = unit.path("skillInfoObjectList");
                for (int k = 0; k < objs.size(); k++)
                    out.add(new Ev(si, t, u, slot, k, skillPtr(si) + "/phasesInfo/" + t + "/skillUnitCollection/" + u + "/skillInfoObjectList/" + k, objs.get(k)));
            }
        }
        return out;
    }

    /** Length of a turn = max(endFrame)+1 over its events. */
    public static int turnLength(JsonNode waz, int si, int t) {
        int m = 0;
        for (Ev e : events(waz, si, t)) m = Math.max(m, e.end() + 1);
        return m;
    }

    /** Pointer of the sub-object data with unitSlotNum == idx (e.g. Hit 6 = 攻撃力), or null. */
    public static String subPtr(JsonNode root, String objPtr, int idx) {
        JsonNode o = Json.at(root, objPtr);
        String key = Json.subListKey(o);
        if (key == null) return null;
        int k = Json.subIndex(o.get(key), idx);
        return k < 0 ? null : Json.child(objPtr, key) + "/" + k + "/data";
    }

    /** Pointer of 威力 (Hit sub 6 Term paramList[0]) or null. */
    public static String damagePtr(JsonNode root, String hitPtr) {
        String s = subPtr(root, hitPtr, 6);
        if (s == null) return null;
        JsonNode pl = root.at(s + "/bsdxInfoCollectionList/0/paramList");
        return pl.isArray() && pl.size() > 0 ? s + "/bsdxInfoCollectionList/0/paramList/0" : null;
    }

    public static Ev firstHit(JsonNode waz, int si) {
        for (Ev e : events(waz, si, -1)) if (e.typeId() == 16 && e.slot == 41) return e;
        return null;
    }
}
