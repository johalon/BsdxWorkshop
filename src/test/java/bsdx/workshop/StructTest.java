package bsdx.workshop;

import bsdx.workshop.data.*;
import bsdx.workshop.pac.PacArchive;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.*;
import java.util.*;

import static bsdx.workshop.SelfTest.check;

/** v2 structural editing: skills / turns / events / weapons / AI with index fix-up, export validation and undo. */
public class StructTest {
    static Project.Mech mech(Project p, String name) {
        for (Project.Mech m : p.mechs) if (m.mekFile != null && m.name.equalsIgnoreCase(name)) return m;
        return null;
    }

    static JsonNode orig(Project p, FileDoc d) throws Exception { return p.codec.parseToTree(d.name, d.originalBytes); }

    static boolean exportOk(Project p, String what) {
        Exporter.Plan plan = new Exporter(p, false).prepare();
        boolean ok = plan.ok();
        check(ok, what + ": export validation OK (generate→re-parse equal, no new mek_check violations) " + (ok ? plan.report.size() + " report lines" : plan.errors + " " + plan.blocking));
        return ok;
    }

    static void run(Codec codec, Fields fields, Path stage) throws Exception {
        System.out.println("[v2 structural editing]");
        Project p = Loader.open(codec, fields, stage, true, null, null);
        p.buildRefIndex();
        Structure st = new Structure(p);

        // ---------------------------------------------------------------- skills (Zako102a: owns waz 63, jumps to itself explicitly)
        Project.Mech z = mech(p, "Zako102a");
        FileDoc zw = p.doc(p.wazFile(z.wazIdx)), zm = p.doc(z.mekFile);
        int n0 = zw.tree.path("skillList").size();
        JsonNode zwOrig = orig(p, zw), zmOrig = orig(p, zm);
        // collect jumps / weapons before
        List<int[]> jumpsBefore = jumps(zw.tree, z.wazIdx);
        Map<String, Integer> wsBefore = new TreeMap<>();
        zm.tree.path("mekWeaponInfoMap").fields().forEachRemaining(e -> wsBefore.put(e.getKey(), e.getValue().path("wazSequence").asInt()));
        Structure.Result r = st.duplicateSkill(zw, 3, false);
        System.out.println("    " + r.text().replace("\n", "\n    "));
        check(r.ok && zw.tree.path("skillList").size() == n0 + 1 && p.wazParam(z.wazIdx) == n0 + 1, "duplicate skill 3 → inserted at 4: skills " + n0 + "→" + zw.tree.path("skillList").size() + ", WazaGroup.param " + p.wazParam(z.wazIdx));
        check(Json.diff(zwOrig.path("skillList").get(3).path("phasesInfo"), zw.tree.path("skillList").get(4).path("phasesInfo"), 5).isEmpty() || !jumps(zwOrig.path("skillList").get(3), z.wazIdx).isEmpty(),
                "inserted skill 4 has the turns of skill 3");
        List<int[]> jumpsAfter = jumps(zw.tree, z.wazIdx);
        boolean jok = !jumpsBefore.isEmpty();
        // every jump target t>=4 must now be t+1 (compare skill names behind the targets)
        for (int[] j : jumpsAfter) jok &= WazNav.skillName(zw.tree, j[1]).equals(WazNav.skillName(zwOrig, j[1] >= 4 ? j[1] - 1 : j[1])) || j[1] == 4;
        check(jok, "技ジャンプ targets still point at the same skills (" + jumpsBefore.size() + " jumps)");
        boolean wok = true;
        for (Map.Entry<String, Integer> e : wsBefore.entrySet()) {
            int now = zm.tree.path("mekWeaponInfoMap").path(e.getKey()).path("wazSequence").asInt();
            wok &= now == (e.getValue() >= 4 ? e.getValue() + 1 : e.getValue());
        }
        check(wok, "mek weapon.wazSequence shifted for skills >= 4 (" + wsBefore + ")");
        boolean pok = true;
        JsonNode pairs = zm.tree.path("mekPairBlock").path("unkPair"), pairs0 = zmOrig.path("mekPairBlock").path("unkPair");
        for (int i = 0; i < pairs.size(); i++) { int a = pairs0.get(i).path("int2").asInt(); pok &= pairs.get(i).path("int2").asInt() == (a >= 4 ? a + 1 : a); }
        check(pok, "mekPairBlock (basic motion → skill) shifted");
        exportOk(p, "after skill insert");
        List<TreeDiff.Line> dl = TreeDiff.diff(zwOrig, zw.tree, 500);
        long plus = dl.stream().filter(x -> x.op == '+').count(), minus = dl.stream().filter(x -> x.op == '-').count();
        check(plus == 1 && minus == 0 && dl.stream().anyMatch(x -> x.op == '+' && x.path.equals("/skillList/4")) && dl.size() <= 1 + jumpsBefore.size() * 2,
                "diff vs original (LCS): one '+ /skillList/4' line + the re-pointed jumps only: " + dl);
        List<TreeDiff.Line> dm = TreeDiff.diff(zmOrig, zm.tree, 500);
        check(!dm.isEmpty() && dm.stream().allMatch(x -> x.op == '~' && (x.path.endsWith("/wazSequence") || x.path.endsWith("/int2"))), "mek diff = wazSequence / pair changes only (" + dm.size() + " lines) " + dm);
        // export to pac, re-read, the waz has n0+1 skills and WazaGroup.grp is included
        Path dir = SelfTest.exportDir("gui_out_struct");
        Exporter ex = new Exporter(p, false);
        Path pac = ex.write(ex.prepare(), dir, SelfTest.testSettings(), p.undo.history());
        try (PacArchive a = new PacArchive(pac)) {
            JsonNode re = codec.parseToTree(zw.name, a.read(zw.name));
            JsonNode wg = codec.parseToTree("WazaGroup.grp", a.read("WazaGroup.grp"));
            check(re.path("skillList").size() == n0 + 1 && wg.path("wazaList").get(z.wazIdx).path("param").asInt() == n0 + 1 && a.read(zm.name) != null,
                    "exported pac: " + a.entries().size() + " files (waz/mek/WazaGroup.grp), re-parsed waz has " + re.path("skillList").size() + " skills");
        }
        p.undo.undo();
        check(Json.diff(zwOrig, zw.tree, 5).isEmpty() && Json.diff(zmOrig, zm.tree, 5).isEmpty() && p.wazParam(z.wazIdx) == n0 && p.dirtyDocs().isEmpty(),
                "undo restores waz, mek and WazaGroup exactly (no dirty docs)");
        p.undo.redo();
        r = st.deleteSkill(zw, 4);
        check(r.ok && Json.diff(zwOrig, zw.tree, 5).isEmpty() && Json.diff(zmOrig, zm.tree, 5).isEmpty() && p.wazParam(z.wazIdx) == n0,
                "deleting the inserted copy returns to the original trees: " + r.message);
        int used = zm.tree.path("mekWeaponInfoMap").path("0").path("wazSequence").asInt();
        r = st.deleteSkill(zw, used);
        check(!r.ok && r.message.contains("武装#0"), "deleting a skill used by a weapon is refused: " + r.message.split("\n")[0]);
        r = st.addSkill(zw, zw.tree.path("skillList").size());
        check(r.ok && WazNav.skillName(zw.tree, n0).equals("新しい技") && p.wazParam(z.wazIdx) == n0 + 1, "add a new empty skill at the end");
        exportOk(p, "after adding a new skill");
        r = st.moveSkill(zw, used, 1);
        check(r.ok && zm.tree.path("mekWeaponInfoMap").path("0").path("wazSequence").asInt() == used + 1, "move skill down: weapon #0 follows (" + used + "→" + (used + 1) + ")");
        while (p.undo.canUndo()) p.undo.undo();

        // shared waz: only append
        FileDoc tama = p.doc(p.wazFile(1));
        r = st.duplicateSkill(tama, 0, false);
        check(!r.ok, "shared waz: middle insert refused: " + r.message);
        int tn = tama.tree.path("skillList").size();
        r = st.duplicateSkill(tama, 0, true);
        check(r.ok && p.wazParam(1) == tn + 1, "shared waz: duplicate to the end OK, param " + tn + "→" + p.wazParam(1));
        exportOk(p, "after shared waz append");
        while (p.undo.canUndo()) p.undo.undo();

        // external reference fix-up: a mech waz skill referenced from ANOTHER waz
        RefIndex ri = p.refIndex();
        int tw = -1, ts = -1, srcW = -1;
        outer:
        for (Project.Mech m : p.mechs) {
            if (m.mekFile == null || p.wazFile(m.wazIdx) == null) continue;
            int ns = p.wazParam(m.wazIdx);
            for (int s = 0; s < ns; s++) for (RefIndex.Ref ref : ri.referrers(m.wazIdx, s)) if (ref.srcWaz != m.wazIdx) { tw = m.wazIdx; ts = s; srcW = ref.srcWaz; break outer; }
        }
        if (tw >= 0) {
            FileDoc tdoc = p.doc(p.wazFile(tw)), sdoc = p.doc(p.wazFile(srcW));
            int before = countRefs(sdoc.tree, tw, ts);
            r = st.addSkill(tdoc, 0);
            int after = countRefs(sdoc.tree, tw, ts + 1);
            check(r.ok && before > 0 && after == before && sdoc.isDirty(), "external refs follow: " + sdoc.name + " → " + tdoc.name + " skill " + ts + "→" + (ts + 1) + " (" + before + " refs)");
            exportOk(p, "after insert with external references");
            while (p.undo.canUndo()) p.undo.undo();
        } else System.out.println("    (no cross-waz reference to a mech waz in vanilla data -> synthetic one below)");
        {   // synthetic: point a WazaSelect + a 技ジャンプ of shared Tama01 at Zako102a skill 5, then insert a skill at 0 in Zako102a
            String[] wsPtr = {null}, jpPtr = {null};
            Structure.walk(tama.tree.path("skillList"), "/skillList", (o, ptr) -> { if (wsPtr[0] == null && o.has("wazFileNo") && o.has("wazSequenceNo")) wsPtr[0] = ptr; });
            Structure.walk(zw.tree.path("skillList"), "/skillList", (o, ptr) -> {
                for (int i = 0; i < o.path("bsdxInfoCollectionList2").size(); i++) if (jpPtr[0] == null && Structure.isSkillJump(o.path("bsdxInfoCollectionList2").get(i))) jpPtr[0] = ptr;
            });
            p.undo.set(tama, wsPtr[0] + "/wazFileNo", com.fasterxml.jackson.databind.node.IntNode.valueOf(z.wazIdx), "synthetic");
            p.undo.set(tama, wsPtr[0] + "/wazSequenceNo", com.fasterxml.jackson.databind.node.IntNode.valueOf(5), "synthetic");
            r = st.addSkill(zw, 0);
            int now = tama.tree.at(wsPtr[0] + "/wazSequenceNo").asInt();
            check(r.ok && now == 6 && r.log.stream().anyMatch(x -> x.startsWith(tama.name)), "external WazaSelect in " + tama.name + " → Zako102a skill 5 follows the insert (now " + now + "): " + r.log);
            exportOk(p, "after insert with an external reference");
            while (p.undo.canUndo()) p.undo.undo();
        }

        // ---------------------------------------------------------------- turns
        int sj = -1, tj = -1;
        for (int s = 0; s < zw.tree.path("skillList").size() && sj < 0; s++) {
            List<Integer> t = turnJumps(zw.tree.path("skillList").get(s));
            if (!t.isEmpty() && zw.tree.path("skillList").get(s).path("phasesInfo").size() > 1) { sj = s; tj = t.get(0); }
        }
        if (sj < 0) for (int s = 0; s < zw.tree.path("skillList").size() && sj < 0; s++) if (zw.tree.path("skillList").get(s).path("phasesInfo").size() > 1) sj = s;
        JsonNode sk0 = zw.tree.path("skillList").get(sj).deepCopy();
        int nt = sk0.path("phasesInfo").size();
        r = st.duplicateTurn(zw, sj, 0);
        JsonNode sk1 = zw.tree.path("skillList").get(sj);
        boolean tok = r.ok && sk1.path("phasesInfo").size() == nt + 1 && sk1.path("phaseQuantity").asInt() == nt + 1 && sk1.path("skillSuffixList").size() == sk0.path("skillSuffixList").size() + 1;
        List<Integer> tjs0 = turnJumps(sk0), tjs1 = turnJumps(sk1);
        for (int i = 0; i < tjs0.size() && i < tjs1.size(); i++) tok &= tjs1.get(i) == (tjs0.get(i) >= 1 ? tjs0.get(i) + 1 : tjs0.get(i));
        check(tok, "duplicate turn 0 of skill " + sj + ": turns " + nt + "→" + (nt + 1) + ", phaseQuantity/skillSuffixList updated, ターンジャンプ指定 " + tjs0 + "→" + tjs1);
        exportOk(p, "after turn duplicate");
        r = st.deleteTurn(zw, sj, 0);
        check(r.ok && Json.diff(sk0, zw.tree.path("skillList").get(sj), 5).isEmpty(), "delete the duplicated turn → skill identical to before");
        if (tj >= 0) {
            r = st.deleteTurn(zw, sj, tj);
            check(!r.ok, "deleting a turn that is a jump target is refused: " + r.message.split("\n")[0]);
        }
        r = st.addTurn(zw, sj, nt);
        check(r.ok && zw.tree.path("skillList").get(sj).path("phasesInfo").size() == nt + 1, "add empty turn at the end");
        exportOk(p, "after adding an empty turn");
        while (p.undo.canUndo()) p.undo.undo();

        // ---------------------------------------------------------------- events
        WazNav.Ev ev = WazNav.events(zw.tree, sj, 0).get(0);
        int cnt = zw.tree.at(ev.ptr.substring(0, ev.ptr.lastIndexOf('/'))).size();
        r = st.duplicateEvent(zw, ev.ptr);
        check(r.ok && zw.tree.at(ev.ptr.substring(0, ev.ptr.lastIndexOf('/'))).size() == cnt + 1, "duplicate event " + ev.ptr);
        r = st.moveEvent(zw, r.selPtr, -1);
        check(r.ok, "move event up");
        exportOk(p, "after event duplicate/move");
        r = st.deleteEvent(zw, r.selPtr);
        check(r.ok && Json.diff(zwOrig, zw.tree, 5).isEmpty(), "delete the copy → waz identical to the original");
        // add an event in a slot the turn does not have yet (SE = 23 if absent, else ScreenYure-like slot picked dynamically)
        Set<Integer> have = new HashSet<>();
        for (WazNav.Ev e : WazNav.events(zw.tree, sj, 0)) have.add(e.slot);
        int slot = -1;
        for (int c : new int[]{55, 50, 23, 40, 48}) if (!have.contains(c) && st.templateEvent(zw, c) != null) { slot = c; break; }
        r = st.addEvent(zw, sj, 0, slot, 2, 6);
        boolean aok = r.ok;
        if (aok) {
            JsonNode added = zw.tree.at(r.selPtr);
            aok = added != null && added.path("startFrame").asInt() == 2 && added.path("endFrame").asInt() == 6;
            JsonNode coll = zw.tree.at(WazNav.skillPtr(sj) + "/phasesInfo/0/skillUnitCollection");
            for (int i = 1; i < coll.size(); i++) aok &= coll.get(i - 1).path("unitQuantity").asInt() < coll.get(i).path("unitQuantity").asInt();
        }
        check(aok, "add event to new slot " + slot + " (" + fields.slotName(slot) + ") at frames 2-6, units stay sorted: " + r.message);
        exportOk(p, "after adding an event in a new slot");
        r = st.deleteEvent(zw, r.selPtr);
        check(r.ok && Json.diff(zwOrig, zw.tree, 5).isEmpty(), "delete it again → slot removed, waz identical to the original");
        while (p.undo.canUndo()) p.undo.undo();

        // ---------------------------------------------------------------- weapons (Aki: 1 material entry per weapon)
        Project.Mech aki = mech(p, "Aki");
        FileDoc am = p.doc(aki.mekFile);
        JsonNode amOrig = orig(p, am);
        int nw = am.tree.path("mekWeaponInfoMap").size(), nmt = am.tree.path("mekMaterialBlock").path("trailingEntries").size();
        r = st.duplicateWeapon(aki, 1, true);
        check(r.ok && am.tree.path("mekWeaponInfoMap").size() == nw + 1 && am.tree.path("mekMaterialBlock").path("trailingEntries").size() == nmt + 1
                        && am.tree.path("mekMaterialBlock").path("entries").size() == am.tree.path("mekMaterialBlock").path("regularEntries").size() + nmt + 1,
                "duplicate weapon #1 to the end: weapons " + nw + "→" + (nw + 1) + ", MaterialBlock entry copied");
        exportOk(p, "after weapon duplicate");
        p.undo.undo();
        // remove a weapon used by AI
        int victim = -1;
        Map<Integer, Integer> aiUse = new TreeMap<>();
        for (JsonNode a : am.tree.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == 2) aiUse.merge(e.path("mekWeaponInfoMapNo").asInt(), 1, Integer::sum);
        for (int w : aiUse.keySet()) if (w > 0 && w < nw - 1) { victim = w; break; }
        FileDoc we = p.weaponEquip();
        JsonNode row0 = we == null ? null : we.tree.path("data").path(aki.id).deepCopy();
        int aiTotal = 0;
        for (int c : aiUse.values()) aiTotal += c;
        r = st.removeWeapon(aki, victim, -1);
        System.out.println("    " + r.text().replace("\n", "\n    "));
        int aiAfter = 0, maxNo = -1;
        for (JsonNode a : am.tree.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == 2) { aiAfter++; maxNo = Math.max(maxNo, e.path("mekWeaponInfoMapNo").asInt()); }
        check(r.ok && am.tree.path("mekWeaponInfoMap").size() == nw - 1 && aiAfter == aiTotal - aiUse.get(victim) && maxNo < nw - 1 && r.newViolations.isEmpty(),
                "remove weapon #" + victim + " (used by " + aiUse.get(victim) + " AI attack events): AI events dropped, others renumbered (max " + maxNo + " < " + (nw - 1) + "), no new mek_check violations");
        boolean rowOk = true;
        if (we != null) {
            JsonNode row = we.tree.path("data").path(aki.id);
            for (int s = 0; s < row.size(); s++) { int a = row0.get(s).asInt(); rowOk &= row.get(s).asInt() == (a == victim ? -1 : a > victim ? a - 1 : a); }
        }
        check(we != null && rowOk, "WeaponEquip.dat row " + aki.id + " remapped: " + row0 + " → " + (we == null ? "-" : we.tree.path("data").path(aki.id)));
        exportOk(p, "after weapon remove");
        p.undo.undo();
        r = st.removeWeapon(aki, victim, 0);
        int to0 = 0;
        for (JsonNode a : am.tree.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == 2 && e.path("mekWeaponInfoMapNo").asInt() == 0) to0++;
        check(r.ok && to0 == aiUse.getOrDefault(0, 0) + aiUse.get(victim), "remove with replacement: AI events re-pointed to weapon #0 (" + to0 + ")");
        p.undo.undo();
        check(Json.diff(amOrig, am.tree, 5).isEmpty() && (we == null || !we.isDirty()), "undo restores Aki.mek and WeaponEquip.dat");
        r = st.moveWeapon(aki, 0, 1);
        check(r.ok && am.tree.path("mekWeaponInfoMap").path("1").path("weaponName").asText().equals(amOrig.path("mekWeaponInfoMap").path("0").path("weaponName").asText()), "move weapon #0 down");
        exportOk(p, "after weapon move");
        p.undo.undo();
        r = st.duplicateWeapon(p.mechs.get(0), 0, true);
        check(!r.ok && r.message.contains("MaterialBlock"), "Kou (3 material entries per Lv weapon): weapon add refused with explanation");

        // ---------------------------------------------------------------- AI
        int ne = am.tree.path("mekAiInfoList").get(0).path("cpuEventList").size();
        r = st.aiEventOp(aki, 0, "addAttack", -1, 0);
        check(r.ok && am.tree.path("mekAiInfoList").get(0).path("cpuEventList").size() == ne + 1 && am.tree.at(r.selPtr).path("type").asInt() == 2, "AI: add attack event");
        r = st.aiEventOp(aki, 0, "addMove", -1, 0);
        check(r.ok && am.tree.at(r.selPtr).path("type").asInt() == 1, "AI: add move event");
        r = st.aiEventOp(aki, 0, "dup", 0, 0);
        r = st.aiEventOp(aki, 0, "move", 1, 1);
        r = st.aiEventOp(aki, 0, "del", 2, 0);
        check(r.ok && am.tree.path("mekAiInfoList").get(0).path("cpuEventList").size() == ne + 2, "AI: duplicate / move / delete events");
        r = st.aiTypeOp(aki, 0, false);
        check(r.ok && am.tree.path("mekAiInfoList").size() == 3, "AI: duplicate AI type to the end");
        r = st.aiTypeOp(aki, 0, true);
        check(!r.ok, "AI: deleting a non-last AI type refused");
        exportOk(p, "after AI edits");
        while (p.undo.canUndo()) p.undo.undo();
        check(p.dirtyDocs().isEmpty(), "everything undone: no dirty docs");

        // ---------------------------------------------------------------- quality: search, settings persistence
        System.out.println("[v2 search / settings]");
        SearchIndex si = new SearchIndex(p);
        long t0 = System.currentTimeMillis();
        si.build();
        List<SearchIndex.Hit> hits = si.search("ライフル", 500);
        long w = hits.stream().filter(h -> h.kind.equals("武装")).count(), s = hits.stream().filter(h -> h.kind.equals("技")).count();
        check(si.isBuilt() && w > 5 && s > 5, "search 「ライフル」: " + w + " weapons, " + s + " skills (index " + (System.currentTimeMillis() - t0) + "ms), e.g. " + hits.get(0));
        // a live (edited) name is found
        FileDoc am2 = p.doc(aki.mekFile);
        p.undo.set(am2, "/mekWeaponInfoMap/0/weaponName", com.fasterxml.jackson.databind.node.TextNode.valueOf("テスト検索武装"), "rename");
        check(si.search("テスト検索", 10).size() == 1, "search sees the edited (unsaved) weapon name");
        p.undo.undo();
        Path sf = Files.createTempFile("bsdxws", ".properties");
        Settings a = new Settings(sf);
        Settings.remember(a.recentOverlays, "J:\\x\\Update3.pac");
        Settings.remember(a.recentOverlays, "J:\\y\\Update3.pac");
        Settings.remember(a.recentOverlays, "j:\\x\\update3.pac");
        a.windowBounds = "10,20,1200,800"; a.lastMech = 77; a.maximized = true;
        Settings.remember(a.recentGameDirs, "C:\\Games\\BSDX");
        a.save();
        Settings b = Settings.load(sf);
        check(b.recentOverlays.size() == 2 && b.recentOverlays.get(0).equals("j:\\x\\update3.pac") && b.windowBounds.equals("10,20,1200,800") && b.lastMech == 77 && b.maximized && b.recentGameDirs.size() == 1,
                "settings: recent files (dedup, most recent first), window bounds, last mech persist: " + b.recentOverlays);
        Files.deleteIfExists(sf);
        p.fs.close();

        // ---------------------------------------------------------------- ported mech in the overlay (透 / phase3b)
        if (Files.isRegularFile(SelfTest.OVERLAY)) {
            System.out.println("[v2 structural editing on the overlay mech]");
            Project q = Loader.open(codec, fields, stage, true, SelfTest.OVERLAY, null);
            q.buildRefIndex();
            Project.Mech pm = null;
            for (Project.Mech m : q.mechs) if (m.mekFile != null && q.fs.layerOf(m.mekFile) == q.fs.getOverlay()) { pm = m; break; }
            Structure sq = new Structure(q);
            FileDoc pw = q.doc(q.wazFile(pm.wazIdx)), pmk = q.doc(pm.mekFile);
            int w0 = pmk.tree.path("mekWeaponInfoMap").path("0").path("wazSequence").asInt();
            Structure.Result r1 = sq.duplicateSkill(pw, w0, false);
            Structure.Result r2 = sq.duplicateWeapon(pm, 0, true);
            Structure.Result r3 = sq.removeWeapon(pm, 1, -1);
            check(r1.ok && r2.ok && r3.ok && r1.newViolations.isEmpty() && r2.newViolations.isEmpty() && r3.newViolations.isEmpty(),
                    pm.mekFile + " (" + pmk.tree.path("mekWeaponInfoMap").size() + " weapons): duplicate skill " + w0 + ", duplicate weapon #0, remove weapon #1 — no new violations");
            Exporter.Plan plan = new Exporter(q, false).prepare();
            check(plan.ok(), "overlay mech: export validation OK " + plan.errors + plan.blocking);
            q.fs.close();
        }
    }

    static List<int[]> jumps(JsonNode n, int self) {
        List<int[]> l = new ArrayList<>();
        walk(n, o -> {
            for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                for (JsonNode t : o.path(k))
                    if (t.path("int1").asInt() == 16 && t.path("typeList").size() > 0 && t.path("typeList").get(0).asInt() == 1 && t.path("paramList").size() >= 2
                            && (t.get("paramList").get(0).asInt() == self || t.get("paramList").get(0).asInt() == -1))
                        l.add(new int[]{t.get("paramList").get(0).asInt(), t.get("paramList").get(1).asInt()});
        });
        return l;
    }

    static List<Integer> turnJumps(JsonNode n) {
        List<Integer> l = new ArrayList<>();
        walk(n, o -> {
            for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                for (JsonNode t : o.path(k)) {
                    JsonNode tl = t.path("typeList");
                    if (t.path("int1").asInt() == 16 && tl.size() > 1 && tl.get(0).asInt() == 0 && tl.get(1).asInt() == 1 && t.path("paramList").size() > 0) l.add(t.get("paramList").get(0).asInt());
                }
        });
        return l;
    }

    static int countRefs(JsonNode tree, int w, int s) {
        int[] c = {0};
        walk(tree, o -> {
            if (o.has("wazFileNo") && o.path("wazFileNo").asInt() == w && o.path("wazSequenceNo").asInt() == s) c[0]++;
            for (String k : new String[]{"bsdxInfoCollectionList", "bsdxInfoCollectionList1", "bsdxInfoCollectionList2"})
                for (JsonNode t : o.path(k))
                    if (t.path("int1").asInt() == 16 && t.path("typeList").size() > 0 && t.path("typeList").get(0).asInt() == 1 && t.path("paramList").size() >= 2
                            && t.get("paramList").get(0).asInt() == w && t.get("paramList").get(1).asInt() == s) c[0]++;
        });
        return c[0];
    }

    static void walk(JsonNode n, java.util.function.Consumer<JsonNode> f) {
        if (n == null) return;
        if (n.isObject()) { f.accept(n); n.elements().forEachRemaining(c -> { if (c.isContainerNode()) walk(c, f); }); }
        else if (n.isArray()) for (JsonNode c : n) if (c.isContainerNode()) walk(c, f);
    }
}
