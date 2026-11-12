package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.giga.nexas.dto.bsdx.spm.Spm;

import java.util.*;
import java.util.function.Consumer;

/**
 * Java port of the essential rules of tools/mek_check.py (index-range lint of a mech against the registries).
 * <pre>
 * W1 weapon.wazSequence &lt; #skills   W2 switchToMekNo in -1..#MekaGroup-1
 * A1 AI attack event weapon index &lt; #weapons (the 蒼 crash cause)   A2 AI Term group/item in range
 * V1 VoiceInfo.version &lt; #BatVoice   V2 voice table groupId &lt; #voices of that character
 * M1 MaterialBlock dims == (#SpriteGroup,#SeGroup,#BatVoice)  M2 material SE exist  M3 material voice &lt; #voices  M4 sprite group empty
 * R1 WazaSelect (file,seq) registered and seq &lt; WazaGroup.param   R2 SE (cat,item) exists   R3 voice exists
 * R4 sprite spmFileSequence registered   R5 Term group/item in range   R8 技ジャンプ in range   R9 anim/pattern exist in the spm
 * R6/R7 informational (not blocking)
 * </pre>
 * Export blocks only violations that are NEW compared with the unedited file (vanilla has a few, notes/08 §3).
 */
public final class MekCheck {
    public static final Set<String> INFO_ONLY = Set.of("R6", "R7");

    public static final class Violation {
        public final String code, msg;
        Violation(String code, String msg) { this.code = code; this.msg = msg; }
        public boolean blocking() { return !INFO_ONLY.contains(code); }
        public String key() { return code + " " + msg; }
        @Override public String toString() { return key(); }
    }

    private final Project p;
    private final List<Violation> out = new ArrayList<>();
    private final boolean spmCheck;

    public MekCheck(Project p, boolean spmCheck) { this.p = p; this.spmCheck = spmCheck; }

    private void add(String code, String msg) { out.add(new Violation(code, msg)); }

    static void walk(JsonNode n, Consumer<JsonNode> f) {
        if (n == null) return;
        if (n.isObject()) {
            f.accept(n);
            n.elements().forEachRemaining(c -> { if (c.isContainerNode()) walk(c, f); });
        } else if (n.isArray()) {
            for (JsonNode c : n) if (c.isContainerNode()) walk(c, f);
        }
    }

    private int nSprite() { return p.spriteList().size(); }
    private int nSe() { return p.seList().size(); }
    private int nVoiceChars() { return p.voiceList().size(); }
    private int nVoices(int ch) { return ch < 0 || ch >= nVoiceChars() ? 0 : p.voiceList().get(ch).path("voices").size(); }
    private boolean seExists(int c, int it) {
        if (c < 0 || c >= nSe()) return false;
        JsonNode items = p.seList().get(c).path("seItems");
        return it >= 0 && it < items.size() && Json.i(items.get(it), "existFlag", 0) != 0;
    }
    private boolean wazRegistered(int f) {
        return f >= 0 && f < p.wazaList().size() && Json.i(p.wazaList().get(f), "existFlag", 0) != 0;
    }
    private boolean spriteRegistered(int g) {
        return g >= 0 && g < nSprite() && Json.i(p.spriteList().get(g), "existFlag", 0) != 0;
    }

    private void termCheck(JsonNode o, String code, String where) {
        walk(o, d -> {
            for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"}) {
                for (JsonNode t : d.path(k)) {
                    if (!t.isObject() || !t.has("int1")) continue;
                    int g = t.get("int1").asInt();
                    if (g < 0 || g >= p.termList().size()) { add(code, where + " term group " + g + " out of range"); continue; }
                    JsonNode tl = t.path("typeList");
                    int items = p.termList().get(g).path("termItemList").size();
                    if (tl.size() > 0 && (tl.get(0).asInt() < 0 || tl.get(0).asInt() >= items))
                        add(code, where + " term " + Json.s(p.termList().get(g), "termGroupCodeName", "" + g) + "[" + tl.get(0).asInt() + "] item out of range (" + items + " items)");
                }
            }
        });
    }

    private void jumps(JsonNode s, Consumer<int[]> f) {
        walk(s, d -> d.fields().forEachRemaining(e -> {
            if (e.getKey().contains("InfoCollectionList") && e.getValue().isArray())
                for (JsonNode t : e.getValue())
                    if (t.isObject() && t.path("int1").asInt(-1) == 16 && t.path("typeList").size() > 0 && t.path("typeList").get(0).asInt() == 1
                            && t.path("paramList").size() >= 2)
                        f.accept(new int[]{t.get("paramList").get(0).asInt(), t.get("paramList").get(1).asInt()});
        }));
    }

    private void refChecks(JsonNode waz, String label, int selfWaz, int selfSpm) {
        JsonNode sl = waz.path("skillList");
        for (int si = 0; si < sl.size(); si++) {
            JsonNode s = sl.get(si);
            if (s == null || !s.isObject() || s.size() == 0) continue;
            final int fsi = si;
            jumps(s, fq -> {
                int f = fq[0] == -1 ? selfWaz : fq[0];
                if (!wazRegistered(f) || fq[1] < 0 || fq[1] >= p.wazParam(f))
                    add("R8", label + " skill " + fsi + " JUMP (" + f + "," + fq[1] + ") out of range");
            });
            if (spmCheck) {
                walk(s, d -> {
                    if (d.has("spmFileSequence") && d.has("actionGroupNumber")) {
                        int g = d.get("spmFileSequence").asInt();
                        if (g == -1) g = selfSpm;
                        if (g < 0 || g >= nSprite()) return;
                        String fn = p.spmFile(g);
                        Spm sp = fn == null ? null : p.spm(fn);
                        if (sp == null) return;   // spm not available (System.pac not mounted) -> skipped
                        int a = d.get("actionGroupNumber").asInt(), pt = d.path("actionNumber").asInt();
                        List<Spm.SPMAnimData> an = sp.getAnimData();
                        if (a < 0 || an == null || a >= an.size() || pt < 0 || pt >= Math.max(1, an.get(a).getNumPat() & 0xFFFF))
                            add("R9", label + " skill " + fsi + " sprite g" + g + " anim " + a + " pat " + pt + " out of range");
                    }
                });
            }
        }
    }

    /** Full mech check (mek_check.py main path). */
    public List<Violation> checkMech(JsonNode mek, JsonNode waz) {
        out.clear();
        JsonNode b = mek.path("mekBasicInfo");
        int selfWaz = Json.i(b, "wazFileSequence", -1), selfSpm = Json.i(b, "spmFileSequence", -1);
        int nsk = waz == null ? -1 : waz.path("skillList").size();
        JsonNode W = mek.path("mekWeaponInfoMap");
        int nW = W.size();
        W.fields().forEachRemaining(e -> {
            JsonNode w = e.getValue();
            int ws = Json.i(w, "wazSequence", -1);
            if (ws < 0 || ws >= nsk) add("W1", "weapon " + e.getKey() + " " + Json.s(w, "weaponName", "") + " wazSequence " + ws);
            int sw = Json.i(w, "switchToMekNo", -1);
            if (sw < -1 || sw >= p.mechs.size()) add("W2", "weapon " + e.getKey() + " switchToMekNo " + sw);
        });
        for (JsonNode a : mek.path("mekAiInfoList")) {
            JsonNode ev = a.path("cpuEventList");
            for (int j = 0; j < ev.size(); j++) {
                JsonNode e = ev.get(j);
                if (e.path("type").asInt() == 2) {
                    int no = e.path("mekWeaponInfoMapNo").asInt(-1);
                    if (no < 0 || no >= nW) add("A1", "AI " + Json.s(a, "aiTypeEnglish", "") + " ev" + j + " attack weapon #" + no + " (have " + nW + ")");
                }
                termCheck(e, "A2", "AI " + Json.s(a, "aiTypeEnglish", "") + " ev" + j);
            }
        }
        JsonNode v = mek.path("mekVoiceInfo");
        if (v.has("version") && v.get("version").asInt() >= 0) {
            int ver = v.get("version").asInt();
            if (ver >= nVoiceChars()) add("V1", "version " + ver + " >= " + nVoiceChars() + " chars");
            else {
                int nv = nVoices(ver);
                TreeSet<Integer> bad = new TreeSet<>();
                int[] mx = {-1};
                walk(v.path("table"), c -> {
                    if (c.has("groupId")) {
                        int g = c.get("groupId").asInt();
                        mx[0] = Math.max(mx[0], g);
                        if (g < 0 || g >= nv) bad.add(g);
                    }
                });
                if (!bad.isEmpty()) add("V2", "BatVoice[" + ver + "] has " + nv + " voices but table uses groupId up to " + mx[0] + " (bad: " + bad.size() + " ids)");
            }
        }
        JsonNode entries = mek.path("mekMaterialBlock").path("entries");
        for (int n = 0; n < entries.size(); n++) {
            JsonNode e = entries.get(n);
            int ds = e.path("spriteGroups").size(), dse = e.path("seGroups").size(), dv = e.path("voiceGroups").size();
            if (ds != nSprite() || dse != nSe() || dv != nVoiceChars()) {
                add("M1", "entry " + n + " dims (" + ds + "," + dse + "," + dv + ") != (" + nSprite() + "," + nSe() + "," + nVoiceChars() + ")");
                continue;
            }
            for (int g = 0; g < ds; g++) if (e.path("spriteGroups").get(g).size() > 0 && !spriteRegistered(g)) add("M4", "entry " + n + " sprite group " + g + " empty");
            for (int c = 0; c < dse; c++) for (JsonNode it : e.path("seGroups").get(c)) if (!seExists(c, it.asInt())) add("M2", "entry " + n + " se (" + c + "," + it.asInt() + ")");
            for (int c = 0; c < dv; c++) for (JsonNode it : e.path("voiceGroups").get(c)) if (it.asInt() < 0 || it.asInt() >= nVoices(c)) add("M3", "entry " + n + " voice (" + c + "," + it.asInt() + ") >= " + nVoices(c));
        }
        if (waz != null) {
            refChecks(waz, "own waz", selfWaz, selfSpm);
            wazRefs(waz, selfWaz, selfSpm, true);
        }
        return new ArrayList<>(out);
    }

    private void wazRefs(JsonNode waz, int selfWaz, int selfSpm, boolean ownMech) {
        JsonNode sl = waz.path("skillList");
        for (int si = 0; si < sl.size(); si++) {
            JsonNode s = sl.get(si);
            if (s == null || !s.isObject() || s.size() == 0) continue;
            final int fsi = si;
            walk(s, d -> {
                int t = d.path("typeId").asInt(-1);
                if (d.has("wazFileNo") && d.has("wazSequenceNo")) {
                    int f = d.get("wazFileNo").asInt(), q = d.get("wazSequenceNo").asInt();
                    if (f == -1) f = selfWaz;
                    if (!wazRegistered(f)) add("R1", "skill " + fsi + " WazaSelect file " + f + " unregistered");
                    else if (q < 0 || q >= p.wazParam(f)) add("R1", "skill " + fsi + " WazaSelect " + Json.s(p.wazaList().get(f), "wazaDisplayName", "") + "[" + q + "] >= " + p.wazParam(f));
                } else if (d.has("spmFileSequence") && d.has("actionGroupNumber")) {
                    int g = d.get("spmFileSequence").asInt();
                    if (g == -1) g = selfSpm;
                    if (g >= 0 && !spriteRegistered(g)) add("R4", "skill " + fsi + " sprite group " + g + " unregistered");
                } else if (t == 23 && d.has("byteDataList")) {
                    for (JsonNode x : d.get("byteDataList")) {
                        int[] ci = Json.bytesToInts(x, 2);
                        if (!seExists(ci[0], ci[1])) add("R2", "skill " + fsi + " SE (" + ci[0] + "," + ci[1] + ")");
                    }
                } else if (t == 24 && d.has("byteDataList")) {
                    for (JsonNode x : d.get("byteDataList")) {
                        int[] ci = Json.bytesToInts(x, 2);
                        if (ci[0] < 0 || ci[0] >= nVoiceChars() || ci[1] < 0 || ci[1] >= nVoices(ci[0])) add("R3", "skill " + fsi + " voice (" + ci[0] + "," + ci[1] + ")");
                    }
                }
                // Hit sub 28 (ヒットＳＥ) is a CEventSe object nested in the Hit -> handled by the t==23 branch above.
                JsonNode hu = d.path("ceventHitUnitList");
                for (JsonNode u : hu) {
                    JsonNode dd = u.path("data");
                    if (u.path("unitSlotNum").asInt() == 6)
                        for (JsonNode tt : dd.path("bsdxInfoCollectionList"))
                            if (tt.path("int1").asInt() != 21) add("R7", "skill " + fsi + " hit attack term group " + tt.path("int1").asInt() + " (BSDX expects 21)");
                }
            });
            termCheck(s, "R5", "skill " + si);
        }
    }

    /** --also-waz path: a shared waz (Tama01, Effect ...) checked on its own. */
    public List<Violation> checkSharedWaz(JsonNode waz, int wazIdx) {
        out.clear();
        String label = Json.s(p.wazaList().get(wazIdx), "wazaDisplayName", "waz" + wazIdx);
        int n = waz.path("skillList").size();
        if (n != p.wazParam(wazIdx)) add("R1", label + " has " + n + " skills but WazaGroup.param " + p.wazParam(wazIdx));
        refChecks(waz, label, wazIdx, -1);
        wazRefs(waz, wazIdx, -1, false);
        return new ArrayList<>(out);
    }

    /** Violations in {@code now} that are not in {@code base} (multiset difference on code+message). */
    public static List<Violation> newViolations(List<Violation> base, List<Violation> now) {
        Map<String, Integer> cnt = new HashMap<>();
        for (Violation v : base) cnt.merge(v.key(), 1, Integer::sum);
        List<Violation> res = new ArrayList<>();
        for (Violation v : now) {
            Integer c = cnt.get(v.key());
            if (c != null && c > 0) cnt.put(v.key(), c - 1); else res.add(v);
        }
        return res;
    }
}
