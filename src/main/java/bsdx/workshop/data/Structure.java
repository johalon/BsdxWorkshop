package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * Structural editing (v2): insert / duplicate / delete of skills, turns, events and mek weapons, AI events,
 * with automatic fix-up of every index that refers to the moved items:
 * <ul>
 * <li>skill index: WazaSelect (bullet / effect / hit effect) in every waz, 技ジャンプ Term (JUMP[1] $v1,$v2),
 *     mek weapon.wazSequence and mekPairBlock (basic motion -> skill) of the mechs that own the waz, WazaGroup.param</li>
 * <li>turn index: ターンジャンプ 指定 (JUMP[0][1] $v1) inside the skill, skillSuffixList, phaseQuantity</li>
 * <li>weapon index: AI attack events (mekWeaponInfoMapNo), WeaponEquip.dat row of the mech, MaterialBlock trailing entries (1 per weapon)</li>
 * </ul>
 * Every operation is ONE undo step (big lists are recorded shallowly, see UndoStack). After the operation MekCheck is run on the
 * affected mechs / shared waz and violations that did not exist before are reported (the caller may offer undo).
 */
public final class Structure {
    public static final class Result {
        public boolean ok;
        public String message = "";
        public final List<String> log = new ArrayList<>();
        public final List<MekCheck.Violation> newViolations = new ArrayList<>();
        /** Selection hint for the UI after the operation (skill / turn / event pointer). */
        public int selSkill = -1, selTurn = -1;
        public String selPtr;
        public String selWeapon;
        static Result fail(String m) { Result r = new Result(); r.ok = false; r.message = m; return r; }
        public boolean hasBlocking() { for (MekCheck.Violation v : newViolations) if (v.blocking()) return true; return false; }
        public String text() {
            StringBuilder sb = new StringBuilder(message);
            for (String l : log) sb.append("\n  ").append(l);
            if (!newViolations.isEmpty()) {
                sb.append("\n\nmek_check で新しい違反が見つかりました（書き出しはブロックされます）:");
                for (MekCheck.Violation v : newViolations) sb.append("\n  ").append(v);
            }
            return sb.toString();
        }
    }

    private final Project p;
    private final boolean spmCheck;

    public Structure(Project p) { this(p, false); }
    public Structure(Project p, boolean spmCheck) { this.p = p; this.spmCheck = spmCheck; }

    // =================================================================== helpers
    public interface PtrVisitor { void visit(JsonNode n, String ptr); }

    public static void walk(JsonNode n, String ptr, PtrVisitor f) {
        if (n == null) return;
        if (n.isObject()) {
            f.visit(n, ptr);
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isContainerNode()) walk(e.getValue(), Json.child(ptr, e.getKey()), f);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) if (n.get(i).isContainerNode()) walk(n.get(i), ptr + "/" + i, f);
        }
    }

    public static boolean isSkillJump(JsonNode t) {
        return t.path("int1").asInt(-1) == 16 && t.path("typeList").size() > 0 && t.path("typeList").get(0).asInt() == 1 && t.path("paramList").size() >= 2 && t.has("typeList");
    }

    static boolean isTurnJumpAbs(JsonNode t) {
        JsonNode tl = t.path("typeList");
        return t.path("int1").asInt(-1) == 16 && tl.size() > 1 && tl.get(0).asInt() == 0 && tl.get(1).asInt() == 1 && t.path("paramList").size() >= 1;
    }

    /** Pending value sets (collected first, then applied, so walking never sees half-applied state). */
    private static final class Sets {
        final List<Object[]> l = new ArrayList<>();
        void add(FileDoc d, String ptr, int v) { l.add(new Object[]{d, ptr, v}); }
        void apply(UndoStack u) { for (Object[] o : l) u.set((FileDoc) o[0], (String) o[1], IntNode.valueOf((Integer) o[2]), ""); }
        int size() { return l.size(); }
    }

    private int wazIdx(FileDoc waz) throws IOException {
        int wi = p.wazIndexOfFile(waz.name);
        if (wi < 0) throw new IOException(waz.name + " は WazaGroup に登録されていません");
        return wi;
    }

    private JsonNode treeOf(String file) throws IOException {
        FileDoc d = p.docIfLoaded(file);
        return d != null ? d.tree : p.codec.parseToTree(file, p.fs.read(file));
    }

    /** mek_check of the given mechs + shared waz on the current trees (labelled). */
    List<MekCheck.Violation> check(Set<Integer> mechIds, Set<Integer> shared) {
        MekCheck mc = new MekCheck(p, spmCheck);
        List<MekCheck.Violation> all = new ArrayList<>();
        for (int id : mechIds) {
            Project.Mech m = p.mechs.get(id);
            if (m.mekFile == null) continue;
            try {
                JsonNode mek = treeOf(m.mekFile);
                String wf = p.wazFile(Json.i(mek.path("mekBasicInfo"), "wazFileSequence", -1));
                JsonNode waz = wf == null ? null : treeOf(wf);
                for (MekCheck.Violation v : mc.checkMech(mek, waz)) all.add(new MekCheck.Violation(v.code, m.mekFile + ": " + v.msg));
            } catch (Exception e) { all.add(new MekCheck.Violation("ERR", m.mekFile + ": " + e)); }
        }
        for (int wi : shared) {
            try {
                String wf = p.wazFile(wi);
                if (wf == null) continue;
                for (MekCheck.Violation v : mc.checkSharedWaz(treeOf(wf), wi)) all.add(new MekCheck.Violation(v.code, wf + ": " + v.msg));
            } catch (Exception e) { all.add(new MekCheck.Violation("ERR", "waz " + wi + ": " + e)); }
        }
        return all;
    }

    private interface Body { void run(Result r) throws Exception; }

    /** Runs a structural change as one undo step with before/after mek_check. */
    private Result run(String label, Set<Integer> mechIds, Set<Integer> shared, Body body) {
        p.syncRefIndex();
        List<MekCheck.Violation> before = check(mechIds, shared);
        Result r = new Result();
        p.undo.begin(label, true);
        try {
            body.run(r);
        } catch (Exception e) {
            p.undo.abort();
            return Result.fail(label + ": できません — " + e.getMessage());
        }
        p.undo.end();
        p.syncRefIndex();
        r.newViolations.addAll(MekCheck.newViolations(before, check(mechIds, shared)));
        r.ok = true;
        if (r.message.isEmpty()) r.message = label;
        return r;
    }

    private Set<Integer> ownerIds(int wi) {
        Set<Integer> s = new TreeSet<>();
        for (Project.Mech m : p.mechsUsingWaz(wi)) s.add(m.id);
        return s;
    }

    // =================================================================== skills
    /** A new, minimal skill: one empty turn. */
    public static ObjectNode newSkill(String nameJa, String nameEn) {
        ObjectNode s = Json.F.objectNode();
        s.put("phaseQuantity", 1);
        s.put("skillNameJapanese", nameJa);
        s.put("skillNameEnglish", nameEn);
        ArrayNode ph = s.putArray("phasesInfo");
        ph.addObject().putArray("skillUnitCollection");
        ArrayNode suf = s.putArray("skillSuffixList");
        ObjectNode e = suf.addObject(); e.put("int1", 0); e.put("int2", 0);
        return s;
    }

    public Result duplicateSkill(FileDoc waz, int si, boolean toEnd) {
        JsonNode s = WazNav.skill(waz.tree, si);
        if (s == null) return Result.fail("技 " + si + " がありません");
        ObjectNode copy = (ObjectNode) s.deepCopy();
        if (copy.has("skillNameJapanese")) copy.put("skillNameJapanese", copy.get("skillNameJapanese").asText() + "（複製）");
        int at = toEnd ? waz.tree.path("skillList").size() : si + 1;
        return insertSkill(waz, at, copy, "技 " + si + "「" + WazNav.skillName(waz.tree, si) + "」を複製 → 技 " + at);
    }

    public Result addSkill(FileDoc waz, int at) {
        return insertSkill(waz, at, newSkill("新しい技", "NEW"), "新しい技を追加 → 技 " + at);
    }

    /** Inserts a skill at {@code at} (0..n) and shifts every reference to skills >= at. */
    public Result insertSkill(FileDoc waz, int at, JsonNode skill, String label) {
        int n = waz.tree.path("skillList").size();
        if (at < 0 || at > n) return Result.fail("挿入位置が範囲外: " + at);
        int wi;
        try { wi = wazIdx(waz); } catch (IOException e) { return Result.fail(e.getMessage()); }
        Set<Integer> owners = ownerIds(wi);
        if (owners.isEmpty() && at != n) return Result.fail("共通 waz（" + waz.name + "）は末尾への追加だけができます（exe や他機体が番号で参照している可能性があるため）");
        Set<Integer> shared = owners.isEmpty() ? Set.of(wi) : Set.of();
        Result res = run(label, owners, shared, r -> {
            ArrayNode nl = Json.F.arrayNode();
            JsonNode old = waz.tree.path("skillList");
            for (int i = 0; i < at; i++) nl.add(old.get(i));
            nl.add(skill.deepCopy());
            for (int i = at; i < n; i++) nl.add(old.get(i));
            IntUnaryOperator map = s -> s >= at ? s + 1 : s;
            if (at < n) remapSkillRefs(waz, wi, map, n, at, r, true);
            p.undo.setShallow(waz, "/skillList", nl, "");
            if (at < n) remapSkillRefs(waz, wi, map, n, at, r, false);
            setParam(wi, n + 1, r);
            r.selSkill = at;
        });
        return res;
    }

    public Result deleteSkill(FileDoc waz, int si) {
        int n = waz.tree.path("skillList").size();
        if (si < 0 || si >= n) return Result.fail("技 " + si + " がありません");
        int wi;
        try { wi = wazIdx(waz); } catch (IOException e) { return Result.fail(e.getMessage()); }
        Set<Integer> owners = ownerIds(wi);
        if (owners.isEmpty() && si != n - 1) return Result.fail("共通 waz（" + waz.name + "）は末尾の技だけ削除できます");
        // references to the deleted skill itself -> refuse
        List<String> users = new ArrayList<>();
        p.syncRefIndex();
        RefIndex ri = refIndex();
        for (RefIndex.Ref ref : ri.referrers(wi, si)) {
            if (ref.srcWaz == wi && ref.srcSkill == si) continue;
            users.add(p.wazLabel(ref.srcWaz) + " 技" + ref.srcSkill + "（" + ref.kind + "）");
        }
        for (int id : owners) {
            try {
                FileDoc mek = p.doc(p.mechs.get(id).mekFile);
                mek.tree.path("mekWeaponInfoMap").fields().forEachRemaining(e -> {
                    if (e.getValue().path("wazSequence").asInt() == si) users.add(mek.name + " 武装#" + e.getKey() + "「" + e.getValue().path("weaponName").asText() + "」");
                });
                JsonNode pairs = mek.tree.path("mekPairBlock").path("unkPair");
                for (int i = 0; i < pairs.size(); i++) if (pairs.get(i).path("int2").asInt() == si && pairs.get(i).path("int1").asInt() == -1) users.add(mek.name + " 基本動作表[" + i + "]");
            } catch (IOException e) { return Result.fail(e.getMessage()); }
        }
        if (!users.isEmpty()) {
            Set<String> u = new LinkedHashSet<>(users);
            return Result.fail("技 " + si + " は次から参照されているので削除できません（先に参照先を変えてください）:\n  " + String.join("\n  ", new ArrayList<>(u).subList(0, Math.min(20, u.size()))) + (u.size() > 20 ? "\n  … 他 " + (u.size() - 20) : ""));
        }
        Set<Integer> shared = owners.isEmpty() ? Set.of(wi) : Set.of();
        String name = WazNav.skillName(waz.tree, si);
        return run("技 " + si + "「" + name + "」を削除", owners, shared, r -> {
            ArrayNode nl = Json.F.arrayNode();
            JsonNode old = waz.tree.path("skillList");
            for (int i = 0; i < n; i++) if (i != si) nl.add(old.get(i));
            IntUnaryOperator map = s -> s > si ? s - 1 : s == si ? -1 : s;
            if (si < n - 1) remapSkillRefs(waz, wi, map, n, si + 1, r, true);
            p.undo.setShallow(waz, "/skillList", nl, "");
            if (si < n - 1) remapSkillRefs(waz, wi, map, n, si + 1, r, false);
            setParam(wi, n - 1, r);
            r.selSkill = Math.min(si, n - 2);
        });
    }

    /** Moves a skill up / down by one (mech waz only); references follow. */
    public Result moveSkill(FileDoc waz, int si, int dir) {
        int n = waz.tree.path("skillList").size(), to = si + dir;
        if (si < 0 || si >= n || to < 0 || to >= n) return Result.fail("これ以上動かせません");
        int wi;
        try { wi = wazIdx(waz); } catch (IOException e) { return Result.fail(e.getMessage()); }
        Set<Integer> owners = ownerIds(wi);
        if (owners.isEmpty()) return Result.fail("共通 waz の技は並べ替えできません");
        return run("技 " + si + " を " + (dir < 0 ? "上" : "下") + "へ移動", owners, Set.of(), r -> {
            ArrayNode nl = Json.F.arrayNode();
            JsonNode old = waz.tree.path("skillList");
            for (int i = 0; i < n; i++) nl.add(old.get(i == si ? to : i == to ? si : i));
            IntUnaryOperator map = s -> s == si ? to : s == to ? si : s;
            int from = Math.min(si, to);
            remapSkillRefs(waz, wi, map, n, from, r, true);
            p.undo.setShallow(waz, "/skillList", nl, "");
            remapSkillRefs(waz, wi, map, n, from, r, false);
            r.selSkill = to;
        });
    }

    private RefIndex refIndex() {
        RefIndex ri = p.refIndex();
        if (ri == null) ri = p.buildRefIndex();
        return ri;
    }

    private void setParam(int wi, int v, Result r) throws IOException {
        FileDoc wg = p.doc("WazaGroup.grp");
        p.undo.set(wg, "/wazaList/" + wi + "/param", IntNode.valueOf(v), "");
        r.log.add("WazaGroup[" + wi + "].param = " + v + "（技数）");
    }

    /**
     * Phase "external" (ownPass=true): fix refs in OTHER waz docs and the owner meks (they are not touched by the
     * skillList replacement). Phase "own" (ownPass=false): fix refs inside the (already replaced) own skillList.
     */
    private void remapSkillRefs(FileDoc waz, int wi, IntUnaryOperator map, int n, int from, Result r, boolean ownPass) throws IOException {
        Sets sets = new Sets();
        if (ownPass) {
            // other waz files referring to (wi, s>=from)
            RefIndex ri = refIndex();
            Set<Integer> srcs = new TreeSet<>();
            for (int s = from; s < n; s++) for (RefIndex.Ref ref : ri.referrers(wi, s)) if (ref.srcWaz != wi) srcs.add(ref.srcWaz);
            for (int src : srcs) {
                String f = p.wazFile(src);
                if (f == null) continue;
                FileDoc d = p.doc(f);
                int before = sets.size();
                walk(d.tree.path("skillList"), "/skillList", (o, ptr) -> {
                    if (o.has("wazFileNo") && o.has("wazSequenceNo") && o.get("wazFileNo").asInt() == wi) {
                        int q = o.get("wazSequenceNo").asInt(), nq = map.applyAsInt(q);
                        if (nq != q && nq >= 0) sets.add(d, ptr + "/wazSequenceNo", nq);
                    }
                    for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                        for (int i = 0; i < o.path(k).size(); i++) {
                            JsonNode t = o.path(k).get(i);
                            if (isSkillJump(t) && t.get("paramList").get(0).asInt() == wi) {
                                int q = t.get("paramList").get(1).asInt(), nq = map.applyAsInt(q);
                                if (nq != q && nq >= 0) sets.add(d, Json.child(ptr, k) + "/" + i + "/paramList/1", nq);
                            }
                        }
                });
                if (sets.size() > before) r.log.add(d.name + ": 参照 " + (sets.size() - before) + " 件を付け替え");
            }
            // owner meks: weapons + basic-motion pairs
            for (Project.Mech m : p.mechsUsingWaz(wi)) {
                FileDoc mek = p.doc(m.mekFile);
                int before = sets.size();
                mek.tree.path("mekWeaponInfoMap").fields().forEachRemaining(e -> {
                    int q = e.getValue().path("wazSequence").asInt(), nq = map.applyAsInt(q);
                    if (nq != q && nq >= 0) sets.add(mek, "/mekWeaponInfoMap/" + e.getKey() + "/wazSequence", nq);
                });
                JsonNode pairs = mek.tree.path("mekPairBlock").path("unkPair");
                for (int i = 0; i < pairs.size(); i++) {
                    JsonNode pr = pairs.get(i);
                    if (pr.path("int1").asInt() != -1 && pr.path("int1").asInt() != wi) continue;
                    int q = pr.path("int2").asInt(), nq = q < 0 ? q : map.applyAsInt(q);
                    if (nq != q && nq >= 0) sets.add(mek, "/mekPairBlock/unkPair/" + i + "/int2", nq);
                }
                if (sets.size() > before) r.log.add(mek.name + ": 武装の技番号・基本動作表 " + (sets.size() - before) + " 件を付け替え");
            }
        } else {
            walk(waz.tree.path("skillList"), "/skillList", (o, ptr) -> {
                if (o.has("wazFileNo") && o.has("wazSequenceNo") && (o.get("wazFileNo").asInt() == wi || o.get("wazFileNo").asInt() == -1)) {
                    int q = o.get("wazSequenceNo").asInt(), nq = map.applyAsInt(q);
                    if (nq != q && nq >= 0) sets.add(waz, ptr + "/wazSequenceNo", nq);
                }
                for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                    for (int i = 0; i < o.path(k).size(); i++) {
                        JsonNode t = o.path(k).get(i);
                        int tw = isSkillJump(t) ? t.get("paramList").get(0).asInt() : -999;
                        if (tw == wi || tw == -1) {
                            int q = t.get("paramList").get(1).asInt(), nq = map.applyAsInt(q);
                            if (nq != q && nq >= 0) sets.add(waz, Json.child(ptr, k) + "/" + i + "/paramList/1", nq);
                        }
                    }
            });
            if (sets.size() > 0) r.log.add(waz.name + "（自身）: 弾・技ジャンプの参照 " + sets.size() + " 件を付け替え");
        }
        sets.apply(p.undo);
    }

    // =================================================================== turns
    static String turnsPtr(int si) { return WazNav.skillPtr(si) + "/phasesInfo"; }

    public Result duplicateTurn(FileDoc waz, int si, int t) {
        JsonNode ph = waz.tree.at(turnsPtr(si));
        if (t < 0 || t >= ph.size()) return Result.fail("ターン " + t + " がありません");
        return insertTurn(waz, si, t + 1, ph.get(t).deepCopy(), "技 " + si + " ターン " + t + " を複製 → ターン " + (t + 1));
    }

    public Result addTurn(FileDoc waz, int si, int at) {
        ObjectNode t = Json.F.objectNode();
        t.putArray("skillUnitCollection");
        return insertTurn(waz, si, at, t, "技 " + si + " に空のターンを追加 → ターン " + at);
    }

    public Result insertTurn(FileDoc waz, int si, int at, JsonNode turn, String label) {
        JsonNode s = WazNav.skill(waz.tree, si);
        if (s == null || !s.has("phasesInfo") || !s.has("skillNameJapanese")) return Result.fail("技 " + si + " は空き（名前なし）です。先に技を作ってください");
        int n = s.path("phasesInfo").size();
        if (at < 0 || at > n) return Result.fail("挿入位置が範囲外");
        return turnOp(waz, si, label, r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < at; i++) nl.add(s.path("phasesInfo").get(i));
            nl.add(turn.deepCopy());
            for (int i = at; i < n; i++) nl.add(s.path("phasesInfo").get(i));
            IntUnaryOperator map = x -> x >= at ? x + 1 : x;
            ArrayNode suf = Json.F.arrayNode();
            boolean added = false;
            for (JsonNode e : s.path("skillSuffixList")) {
                int t = e.path("int1").asInt();
                if (!added && t >= at) { suf.addObject().put("int1", at).put("int2", 0); added = true; }
                ObjectNode c = e.deepCopy(); c.put("int1", map.applyAsInt(t)); suf.add(c);
            }
            if (!added) suf.addObject().put("int1", at).put("int2", 0);
            applyTurns(waz, si, nl, suf, map, r);
            r.selSkill = si; r.selTurn = at;
        });
    }

    public Result deleteTurn(FileDoc waz, int si, int t) {
        JsonNode s = WazNav.skill(waz.tree, si);
        int n = s == null ? 0 : s.path("phasesInfo").size();
        if (t < 0 || t >= n) return Result.fail("ターン " + t + " がありません");
        if (n == 1) return Result.fail("最後の 1 ターンは削除できません（技ごと削除してください）");
        List<String> users = new ArrayList<>();
        for (int tt = 0; tt < n; tt++) {
            if (tt == t) continue;
            final int ftt = tt;
            walk(s.path("phasesInfo").get(tt), "", (o, ptr) -> {
                for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                    for (JsonNode x : o.path(k)) if (isTurnJumpAbs(x) && x.get("paramList").get(0).asInt() == t) users.add("ターン " + ftt + " の「ターンジャンプ→指定 " + t + "」");
            });
        }
        if (!users.isEmpty()) return Result.fail("ターン " + t + " は次から飛び先に指定されているので削除できません:\n  " + String.join("\n  ", users));
        return turnOp(waz, si, "技 " + si + " のターン " + t + " を削除", r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < n; i++) if (i != t) nl.add(s.path("phasesInfo").get(i));
            IntUnaryOperator map = x -> x > t ? x - 1 : x == t ? -1 : x;
            ArrayNode suf = Json.F.arrayNode();
            for (JsonNode e : s.path("skillSuffixList")) {
                int x = e.path("int1").asInt();
                if (x == t) continue;
                ObjectNode c = e.deepCopy(); c.put("int1", map.applyAsInt(x)); suf.add(c);
            }
            applyTurns(waz, si, nl, suf, map, r);
            r.selSkill = si; r.selTurn = Math.min(t, n - 2);
        });
    }

    public Result moveTurn(FileDoc waz, int si, int t, int dir) {
        JsonNode s = WazNav.skill(waz.tree, si);
        int n = s == null ? 0 : s.path("phasesInfo").size(), to = t + dir;
        if (t < 0 || t >= n || to < 0 || to >= n) return Result.fail("これ以上動かせません");
        return turnOp(waz, si, "技 " + si + " のターン " + t + " を" + (dir < 0 ? "前" : "後") + "へ移動", r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < n; i++) nl.add(s.path("phasesInfo").get(i == t ? to : i == to ? t : i));
            IntUnaryOperator map = x -> x == t ? to : x == to ? t : x;
            ArrayNode suf = Json.F.arrayNode();
            for (JsonNode e : s.path("skillSuffixList")) suf.add(e.deepCopy());   // (turn,0) list keeps its order
            applyTurns(waz, si, nl, suf, map, r);
            r.selSkill = si; r.selTurn = to;
        });
    }

    private Result turnOp(FileDoc waz, int si, String label, Body body) {
        int wi = p.wazIndexOfFile(waz.name);
        Set<Integer> owners = wi < 0 ? Set.of() : ownerIds(wi);
        Set<Integer> shared = owners.isEmpty() && wi >= 0 ? Set.of(wi) : Set.of();
        return run(label, owners, shared, body);
    }

    private void applyTurns(FileDoc waz, int si, ArrayNode turns, ArrayNode suffix, IntUnaryOperator map, Result r) {
        String sp = WazNav.skillPtr(si);
        p.undo.setShallow(waz, sp + "/phasesInfo", turns, "");
        p.undo.set(waz, sp + "/phaseQuantity", IntNode.valueOf(turns.size()), "");
        p.undo.set(waz, sp + "/skillSuffixList", suffix, "");
        Sets sets = new Sets();
        walk(waz.tree.at(sp + "/phasesInfo"), sp + "/phasesInfo", (o, ptr) -> {
            for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                for (int i = 0; i < o.path(k).size(); i++) {
                    JsonNode t = o.path(k).get(i);
                    if (isTurnJumpAbs(t)) {
                        int q = t.get("paramList").get(0).asInt(), nq = map.applyAsInt(q);
                        if (nq != q && nq >= 0) sets.add(waz, Json.child(ptr, k) + "/" + i + "/paramList/0", nq);
                    }
                }
        });
        sets.apply(p.undo);
        r.log.add("ターン数 " + turns.size() + "、ターンジャンプ（指定）の付け替え " + sets.size() + " 件");
    }

    // =================================================================== events
    /** Parses ".../skillUnitCollection/u/skillInfoObjectList/k" into {listPtr, unitPtr, collPtr, k, u}. */
    static final class EvPtr {
        final String list, unit, coll; final int k, u;
        EvPtr(String ptr) {
            int a = ptr.lastIndexOf("/skillInfoObjectList/");
            if (a < 0) throw new IllegalArgumentException("イベントではありません: " + ptr);
            list = ptr.substring(0, a) + "/skillInfoObjectList";
            k = Integer.parseInt(ptr.substring(a + "/skillInfoObjectList/".length()));
            unit = ptr.substring(0, a);
            int b = unit.lastIndexOf('/');
            coll = unit.substring(0, b);
            u = Integer.parseInt(unit.substring(b + 1));
        }
    }

    private Result eventOp(FileDoc waz, String label, Body body) {
        int wi = p.wazIndexOfFile(waz.name);
        Set<Integer> owners = wi < 0 ? Set.of() : ownerIds(wi);
        Set<Integer> shared = owners.isEmpty() && wi >= 0 ? Set.of(wi) : Set.of();
        return run(label, owners, shared, body);
    }

    public Result duplicateEvent(FileDoc waz, String ptr) {
        EvPtr e = new EvPtr(ptr);
        JsonNode list = waz.tree.at(e.list);
        return eventOp(waz, "イベントを複製 " + ptr, r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < list.size(); i++) { nl.add(list.get(i)); if (i == e.k) nl.add(list.get(i).deepCopy()); }
            p.undo.setShallow(waz, e.list, nl, "");
            r.selPtr = e.list + "/" + (e.k + 1);
        });
    }

    public Result moveEvent(FileDoc waz, String ptr, int dir) {
        EvPtr e = new EvPtr(ptr);
        JsonNode list = waz.tree.at(e.list);
        int to = e.k + dir;
        if (to < 0 || to >= list.size()) return Result.fail("これ以上動かせません（同じスロット内で並べ替えます）");
        return eventOp(waz, "イベントを" + (dir < 0 ? "上" : "下") + "へ " + ptr, r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < list.size(); i++) nl.add(list.get(i == e.k ? to : i == to ? e.k : i));
            p.undo.setShallow(waz, e.list, nl, "");
            r.selPtr = e.list + "/" + to;
        });
    }

    public Result deleteEvent(FileDoc waz, String ptr) {
        EvPtr e = new EvPtr(ptr);
        JsonNode list = waz.tree.at(e.list);
        JsonNode unit = waz.tree.at(e.unit);
        return eventOp(waz, "イベントを削除 " + ptr, r -> {
            if (list.size() == 1 && unit.path("skillInfoUnknownList").size() == 0) {
                // the slot becomes empty -> drop the unit (the parser does not create empty units)
                JsonNode coll = waz.tree.at(e.coll);
                ArrayNode nc = Json.F.arrayNode();
                for (int i = 0; i < coll.size(); i++) if (i != e.u) nc.add(coll.get(i));
                p.undo.setShallow(waz, e.coll, nc, "");
                r.selPtr = null;
                r.log.add("スロットが空になったので取り除きました");
            } else {
                ArrayNode nl = Json.F.arrayNode();
                for (int i = 0; i < list.size(); i++) if (i != e.k) nl.add(list.get(i));
                p.undo.setShallow(waz, e.list, nl, "");
                r.selPtr = nl.size() == 0 ? null : e.list + "/" + Math.min(e.k, nl.size() - 1);
            }
        });
    }

    /** A template event of a slot: the first one found in the given waz, the mech waz files, then all waz. */
    public JsonNode templateEvent(FileDoc waz, int slot) {
        JsonNode t = findSlotEvent(waz.tree, slot);
        if (t != null) return t;
        List<String> files = new ArrayList<>();
        for (Project.Mech m : p.mechs) { String f = p.wazFile(m.wazIdx); if (f != null && !files.contains(f)) files.add(f); }
        for (int i = 0; i < p.wazaList().size(); i++) { String f = p.wazFile(i); if (f != null && !files.contains(f)) files.add(f); }
        for (String f : files) {
            try {
                t = findSlotEvent(treeOf(f), slot);
                if (t != null) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static JsonNode findSlotEvent(JsonNode waz, int slot) {
        for (JsonNode s : waz.path("skillList"))
            for (JsonNode ph : s.path("phasesInfo"))
                for (JsonNode u : ph.path("skillUnitCollection"))
                    if (u.path("unitQuantity").asInt() == slot && u.path("skillInfoObjectList").size() > 0) return u.path("skillInfoObjectList").get(0);
        return null;
    }

    /** Adds an event of {@code slot} to turn t of skill si (template copied from existing data, frames start..end). */
    public Result addEvent(FileDoc waz, int si, int t, int slot, int start, int end) {
        JsonNode ph = waz.tree.at(turnsPtr(si) + "/" + t);
        if (ph.isMissingNode()) return Result.fail("ターンがありません");
        JsonNode tpl = templateEvent(waz, slot);
        if (tpl == null) return Result.fail("スロット " + slot + " のイベントは、どの waz にも見本がないため追加できません");
        ObjectNode ev = (ObjectNode) tpl.deepCopy();
        ev.put("startFrame", start);
        ev.put("endFrame", end);
        String cp = turnsPtr(si) + "/" + t + "/skillUnitCollection";
        JsonNode coll = waz.tree.at(cp);
        return eventOp(waz, "イベント追加 [" + slot + "] " + p.fields.slotName(slot) + "（技 " + si + " ターン " + t + "）", r -> {
            int found = -1, ins = coll.size();
            for (int i = 0; i < coll.size(); i++) {
                int q = coll.get(i).path("unitQuantity").asInt();
                if (q == slot) found = i;
                if (q > slot && ins == coll.size()) ins = i;
            }
            if (found >= 0) {
                JsonNode list = coll.get(found).path("skillInfoObjectList");
                ArrayNode nl = Json.F.arrayNode();
                list.forEach(nl::add);
                nl.add(ev);
                String lp = cp + "/" + found + "/skillInfoObjectList";
                p.undo.setShallow(waz, lp, nl, "");
                r.selPtr = lp + "/" + (nl.size() - 1);
            } else {
                ObjectNode unit = Json.F.objectNode();
                unit.put("unitQuantity", slot);
                unit.put("unitDescription", slotDescription(slot));
                unit.putArray("skillInfoObjectList").add(ev);
                unit.putArray("skillInfoUnknownList");
                ArrayNode nc = Json.F.arrayNode();
                for (int i = 0; i < coll.size(); i++) { if (i == ins) nc.add(unit); nc.add(coll.get(i)); }
                if (ins == coll.size()) nc.add(unit);
                p.undo.setShallow(waz, cp, nc, "");
                r.selPtr = cp + "/" + ins + "/skillInfoObjectList/0";
                r.log.add("スロット [" + slot + "] を新設");
            }
            r.selSkill = si; r.selTurn = t;
        });
    }

    static String slotDescription(int slot) {
        try {
            return com.giga.nexas.dto.bsdx.waz.wazfactory.SkillInfoFactory.SKILL_INFO_TYPE_ENTRIES_BSDX[slot].getDescription();
        } catch (Throwable e) { return ""; }
    }

    // =================================================================== mek weapons
    static List<String> weaponKeys(JsonNode mek) {
        List<String> ks = new ArrayList<>();
        mek.path("mekWeaponInfoMap").fieldNames().forEachRemaining(ks::add);
        ks.sort(Comparator.comparingInt(k -> { try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 9999; } }));
        return ks;
    }

    /** Why weapons of this mek cannot be added/removed (MaterialBlock not 1 entry per weapon, e.g. Kou), or null. */
    public static String weaponListLock(JsonNode mek) {
        int nW = mek.path("mekWeaponInfoMap").size();
        int nT = mek.path("mekMaterialBlock").path("trailingEntries").size();
        if (nT != nW) return "この機体は MaterialBlock の武装エントリ数（" + nT + "）が武装数（" + nW + "）と一致しません（甲のように Lv ごとに 3 個ずつ持つ機体）。対応が未確定のため、武装の追加・削除はできません（値の編集はできます）。";
        return null;
    }

    public Result duplicateWeapon(Project.Mech m, int idx, boolean toEnd) {
        try {
            FileDoc mek = p.doc(m.mekFile);
            List<String> ks = weaponKeys(mek.tree);
            if (idx < 0 || idx >= ks.size()) return Result.fail("武装 " + idx + " がありません");
            ObjectNode w = (ObjectNode) mek.tree.path("mekWeaponInfoMap").path(ks.get(idx)).deepCopy();
            w.put("weaponName", w.path("weaponName").asText() + "２");
            int at = toEnd ? ks.size() : idx + 1;
            return insertWeapon(m, at, w, idx, "武装 #" + idx + "「" + mek.tree.path("mekWeaponInfoMap").path(ks.get(idx)).path("weaponName").asText() + "」を複製 → #" + at);
        } catch (IOException e) { return Result.fail(e.getMessage()); }
    }

    /** Inserts a weapon at {@code at}; the MaterialBlock entry is copied from weapon {@code materialFrom}. */
    public Result insertWeapon(Project.Mech m, int at, JsonNode weapon, int materialFrom, String label) {
        FileDoc mek;
        try { mek = p.doc(m.mekFile); } catch (IOException e) { return Result.fail(e.getMessage()); }
        String lock = weaponListLock(mek.tree);
        if (lock != null) return Result.fail(lock);
        int n = mek.tree.path("mekWeaponInfoMap").size();
        if (at < 0 || at > n) return Result.fail("挿入位置が範囲外");
        return run(label, Set.of(m.id), Set.of(), r -> {
            List<String> ks = weaponKeys(mek.tree);
            List<JsonNode> ws = new ArrayList<>();
            for (String k : ks) ws.add(mek.tree.path("mekWeaponInfoMap").get(k));
            ws.add(at, weapon.deepCopy());
            JsonNode trailing = mek.tree.path("mekMaterialBlock").path("trailingEntries");
            List<JsonNode> mt = new ArrayList<>();
            trailing.forEach(mt::add);
            int src = materialFrom >= 0 && materialFrom < mt.size() ? materialFrom : Math.max(0, Math.min(at, mt.size()) - 1);
            if (!mt.isEmpty()) mt.add(at, mt.get(src).deepCopy());
            IntUnaryOperator map = x -> x >= at ? x + 1 : x;
            writeWeapons(m, mek, ws, mt, map, -1, -1, r);
            r.selWeapon = "" + at;
        });
    }

    /**
     * Removes weapon {@code idx}. AI attack events that use it are deleted ({@code replacement} &lt; 0) or re-pointed to
     * {@code replacement} (index in the OLD numbering). WeaponEquip slots using it become -1.
     */
    public Result removeWeapon(Project.Mech m, int idx, int replacement) {
        FileDoc mek;
        try { mek = p.doc(m.mekFile); } catch (IOException e) { return Result.fail(e.getMessage()); }
        String lock = weaponListLock(mek.tree);
        if (lock != null) return Result.fail(lock);
        int n = mek.tree.path("mekWeaponInfoMap").size();
        if (idx < 0 || idx >= n) return Result.fail("武装 " + idx + " がありません");
        if (n == 1) return Result.fail("最後の武装は削除できません");
        if (replacement == idx || replacement >= n) return Result.fail("代わりの武装が不正です");
        List<String> ks0 = weaponKeys(mek.tree);
        String name = mek.tree.path("mekWeaponInfoMap").path(ks0.get(idx)).path("weaponName").asText();
        return run("武装 #" + idx + "「" + name + "」を削除", Set.of(m.id), Set.of(), r -> {
            List<String> ks = weaponKeys(mek.tree);
            List<JsonNode> ws = new ArrayList<>();
            for (String k : ks) ws.add(mek.tree.path("mekWeaponInfoMap").get(k));
            ws.remove(idx);
            List<JsonNode> mt = new ArrayList<>();
            mek.tree.path("mekMaterialBlock").path("trailingEntries").forEach(mt::add);
            if (idx < mt.size()) mt.remove(idx);
            IntUnaryOperator map = x -> x > idx ? x - 1 : x == idx ? -1 : x;
            int repl = replacement < 0 ? -1 : map.applyAsInt(replacement);
            writeWeapons(m, mek, ws, mt, map, idx, repl, r);
            r.selWeapon = "" + Math.min(idx, ws.size() - 1);
        });
    }

    public Result moveWeapon(Project.Mech m, int idx, int dir) {
        FileDoc mek;
        try { mek = p.doc(m.mekFile); } catch (IOException e) { return Result.fail(e.getMessage()); }
        String lock = weaponListLock(mek.tree);
        if (lock != null) return Result.fail(lock);
        int n = mek.tree.path("mekWeaponInfoMap").size(), to = idx + dir;
        if (idx < 0 || idx >= n || to < 0 || to >= n) return Result.fail("これ以上動かせません");
        return run("武装 #" + idx + " を" + (dir < 0 ? "上" : "下") + "へ移動", Set.of(m.id), Set.of(), r -> {
            List<String> ks = weaponKeys(mek.tree);
            List<JsonNode> ws = new ArrayList<>(), mt = new ArrayList<>();
            for (int i = 0; i < n; i++) ws.add(mek.tree.path("mekWeaponInfoMap").get(ks.get(i == idx ? to : i == to ? idx : i)));
            JsonNode tr = mek.tree.path("mekMaterialBlock").path("trailingEntries");
            for (int i = 0; i < tr.size(); i++) mt.add(tr.get(i == idx ? to : i == to ? idx : i));
            IntUnaryOperator map = x -> x == idx ? to : x == to ? idx : x;
            writeWeapons(m, mek, ws, mt, map, -1, -1, r);
            r.selWeapon = "" + to;
        });
    }

    private void writeWeapons(Project.Mech m, FileDoc mek, List<JsonNode> ws, List<JsonNode> trailing, IntUnaryOperator map, int removed, int repl, Result r) throws IOException {
        ObjectNode wm = Json.F.objectNode();
        for (int i = 0; i < ws.size(); i++) wm.set("" + i, ws.get(i));
        p.undo.setShallow(mek, "/mekWeaponInfoMap", wm, "");
        // MaterialBlock: trailing entries are 1 per weapon; "entries" = regular + trailing (both kept consistent)
        JsonNode mb = mek.tree.path("mekMaterialBlock");
        if (mb.has("trailingEntries")) {
            ArrayNode tr = Json.F.arrayNode();
            trailing.forEach(tr::add);
            p.undo.setShallow(mek, "/mekMaterialBlock/trailingEntries", tr, "");
            ArrayNode all = Json.F.arrayNode();
            mb.path("regularEntries").forEach(all::add);
            trailing.forEach(all::add);
            p.undo.setShallow(mek, "/mekMaterialBlock/entries", all, "");
            r.log.add("MaterialBlock: 武装エントリ " + trailing.size() + " 個（武装と 1 対 1）");
        }
        // AI attack events
        JsonNode ai = mek.tree.path("mekAiInfoList");
        Sets sets = new Sets();
        int dropped = 0, repointed = 0;
        for (int a = 0; a < ai.size(); a++) {
            JsonNode ev = ai.get(a).path("cpuEventList");
            ArrayNode keep = Json.F.arrayNode();
            boolean drop = false;
            List<int[]> fix = new ArrayList<>();
            for (int j = 0; j < ev.size(); j++) {
                JsonNode e = ev.get(j);
                if (e.path("type").asInt() == 2 && e.has("mekWeaponInfoMapNo")) {
                    int w = e.get("mekWeaponInfoMapNo").asInt(), nw = map.applyAsInt(w);
                    if (w == removed) {
                        if (repl < 0) { drop = true; dropped++; continue; }
                        nw = repl; repointed++;
                    }
                    if (nw != w && nw >= 0) fix.add(new int[]{keep.size(), nw});
                }
                keep.add(e);
            }
            String lp = "/mekAiInfoList/" + a + "/cpuEventList";
            if (drop) p.undo.setShallow(mek, lp, keep, "");
            for (int[] f : fix) sets.add(mek, lp + "/" + f[0] + "/mekWeaponInfoMapNo", f[1]);
        }
        int aiFix = sets.size();
        // WeaponEquip.dat row of this mech
        FileDoc we = p.weaponEquip();
        int weFix = 0;
        if (we != null) {
            JsonNode row = we.tree.path("data").path(m.id);
            for (int s = 0; s < row.size(); s++) {
                int w = row.get(s).asInt();
                if (w < 0) continue;
                int nw = map.applyAsInt(w);
                if (nw != w) { sets.add(we, "/data/" + m.id + "/" + s, nw); weFix++; }
            }
        }
        sets.apply(p.undo);
        r.log.add("AI 攻撃イベント: 番号の付け替え " + aiFix + " 件" + (removed >= 0 ? "、削除した武装を使うイベント: " + (repl < 0 ? "削除 " + dropped + " 件" : "武装 #" + repl + " へ " + repointed + " 件") : ""));
        r.log.add(we == null ? "WeaponEquip.dat: なし（Config.pac が読めない）" : "WeaponEquip.dat 行 " + m.id + ": " + weFix + " 枠を付け替え（削除した武装の枠は -1 = 空き）");
    }

    // =================================================================== AI
    public Result aiEventOp(Project.Mech m, int ai, String kind, int j, int dir) {
        FileDoc mek;
        try { mek = p.doc(m.mekFile); } catch (IOException e) { return Result.fail(e.getMessage()); }
        JsonNode list = mek.tree.path("mekAiInfoList").path(ai).path("cpuEventList");
        if (list.isMissingNode()) return Result.fail("AI " + ai + " がありません");
        String lp = "/mekAiInfoList/" + ai + "/cpuEventList";
        String label;
        switch (kind) {
            case "dup": label = "AI イベント " + j + " を複製"; break;
            case "del": label = "AI イベント " + j + " を削除"; break;
            case "move": label = "AI イベント " + j + " を" + (dir < 0 ? "上" : "下") + "へ"; break;
            case "addAttack": label = "AI 攻撃イベントを追加"; break;
            case "addMove": label = "AI 移動イベントを追加"; break;
            default: return Result.fail(kind);
        }
        JsonNode tpl = null;
        if (kind.startsWith("add")) {
            int type = kind.equals("addAttack") ? 2 : 1;
            tpl = aiTemplate(mek.tree, type);
            if (tpl == null) return Result.fail("見本になる AI イベントがありません");
        }
        if ((kind.equals("dup") || kind.equals("del") || kind.equals("move")) && (j < 0 || j >= list.size())) return Result.fail("イベントを選んでください");
        if (kind.equals("move") && (j + dir < 0 || j + dir >= list.size())) return Result.fail("これ以上動かせません");
        final JsonNode ftpl = tpl;
        return run(label, Set.of(m.id), Set.of(), r -> {
            ArrayNode nl = Json.F.arrayNode();
            int sel = j;
            switch (kind) {
                case "dup": for (int i = 0; i < list.size(); i++) { nl.add(list.get(i)); if (i == j) nl.add(list.get(i).deepCopy()); } sel = j + 1; break;
                case "del": for (int i = 0; i < list.size(); i++) if (i != j) nl.add(list.get(i)); sel = Math.min(j, list.size() - 2); break;
                case "move": { int to = j + dir; for (int i = 0; i < list.size(); i++) nl.add(list.get(i == j ? to : i == to ? j : i)); sel = to; break; }
                default: {
                    list.forEach(nl::add);
                    ObjectNode e = (ObjectNode) ftpl.deepCopy();
                    if (e.has("mekWeaponInfoMapNo")) e.put("mekWeaponInfoMapNo", 0);
                    nl.add(e);
                    sel = nl.size() - 1;
                }
            }
            p.undo.setShallow(mek, lp, nl, "");
            r.selPtr = sel < 0 ? null : lp + "/" + sel;
        });
    }

    JsonNode aiTemplate(JsonNode mek, int type) {
        for (JsonNode a : mek.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == type) return e;
        try {
            Project.Mech kou = p.mechs.get(0);
            JsonNode k = treeOf(kou.mekFile);
            for (JsonNode a : k.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == type) return e;
        } catch (Exception ignored) {}
        return null;
    }

    /** Duplicates an AI type to the end of the list (deleting is allowed for the last one only: scripts select AI by index). */
    public Result aiTypeOp(Project.Mech m, int ai, boolean delete) {
        FileDoc mek;
        try { mek = p.doc(m.mekFile); } catch (IOException e) { return Result.fail(e.getMessage()); }
        JsonNode list = mek.tree.path("mekAiInfoList");
        if (ai < 0 || ai >= list.size()) return Result.fail("AI を選んでください");
        if (delete && (ai != list.size() - 1 || list.size() == 1)) return Result.fail("AI 種類は末尾のものだけ削除できます（スクリプトが番号で AI を指定するため。最後の 1 つは削除不可）");
        return run(delete ? "AI 種類 " + ai + " を削除" : "AI 種類 " + ai + " を末尾に複製", Set.of(m.id), Set.of(), r -> {
            ArrayNode nl = Json.F.arrayNode();
            for (int i = 0; i < list.size(); i++) if (!(delete && i == ai)) nl.add(list.get(i));
            if (!delete) {
                ObjectNode c = (ObjectNode) list.get(ai).deepCopy();
                c.set("aiTypeEnglish", TextNode.valueOf(c.path("aiTypeEnglish").asText() + "2"));
                nl.add(c);
            }
            p.undo.setShallow(mek, "/mekAiInfoList", nl, "");
            r.selPtr = "/mekAiInfoList/" + (delete ? ai - 1 : nl.size() - 1);
        });
    }
}
