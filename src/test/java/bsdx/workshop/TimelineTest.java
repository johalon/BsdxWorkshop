package bsdx.workshop;

import bsdx.workshop.data.*;
import bsdx.workshop.ui.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** v3: skill-level timeline (all turns appended, jumps ignored), concatenated sprite preview, value legends, raw-key labels. */
public class TimelineTest {
    static void check(boolean ok, String what) { SelfTest.check(ok, what); }

    static String ev(int start, int end, int type, String extra) {
        return "{\"typeId\":" + type + ",\"startFrame\":" + start + ",\"endFrame\":" + end + (extra.isEmpty() ? "" : "," + extra) + "}";
    }
    static String unit(int slot, String... evs) { return "{\"unitQuantity\":" + slot + ",\"skillInfoObjectList\":[" + String.join(",", evs) + "]}"; }
    static String turn(String... units) { return "{\"skillUnitCollection\":[" + String.join(",", units) + "]}"; }

    static void run(Codec codec, Fields fields, Path stage, Path overlay) throws Exception {
        System.out.println("[v3 skill timeline / concatenated preview / legends]");
        synthetic(codec.mapper);
        legends(fields);
        Project p = Loader.open(codec, fields, stage, true, Files.isRegularFile(overlay) ? overlay : null, null);
        p.buildRefIndex();
        realData(p);
        ui(p);
    }

    /** Hand-made skill: turn1 holds a 変更処理 that jumps back to turn 0 — the layout must ignore it and just append. */
    static void synthetic(ObjectMapper m) throws Exception {
        String spr = "\"spmFileSequence\":-1,\"actionGroupNumber\":%d,\"actionNumber\":%d";
        String waz = "{\"skillList\":[{\"skillNameJapanese\":\"t\",\"phasesInfo\":["
                + turn(unit(0, ev(0, 5, 13, String.format(spr, 3, 0)), ev(6, 9, 13, String.format(spr, 3, 1))), unit(18, ev(2, 9, 0, "\"int1\":0")))
                + "," + turn(unit(0, ev(0, 3, 13, String.format(spr, 4, 2))), unit(71, ev(3, 3, 34, "\"bsdxInfoCollectionList2\":[{\"typeList\":[0,1],\"paramList\":[0]}]")))
                + "," + turn()
                + "," + turn(unit(5, ev(0, 19, 2, "")), unit(0, ev(4, 6, 13, String.format(spr, 5, 0))))
                + "]}]}";
        JsonNode t = m.readTree(waz);
        SkillSeq s = SkillSeq.all(t, 0);
        check(Arrays.equals(s.turnStart, new int[]{0, 10, 14, 15}) && Arrays.equals(s.turnLen, new int[]{10, 4, 1, 20}) && s.total == 35,
                "synthetic: turns appended in order, jump in turn1 ignored: starts " + Arrays.toString(s.turnStart) + " lens " + Arrays.toString(s.turnLen) + " total " + s.total);
        check(s.slots.equals(List.of(0, 5, 18, 71)), "synthetic: rows = union of slots ascending " + s.slots);
        boolean placed = true;
        for (SkillSeq.Placed pl : s.events) placed &= pl.gStart == s.turnStart[s.indexOfTurn(pl.ev.turn)] + pl.ev.start() && pl.gEnd - pl.gStart == pl.ev.end() - pl.ev.start();
        check(placed && s.events.size() == 7, "synthetic: every event at turnStart + local frame (" + s.events.size() + " events)");
        check(s.sprite.size() == 4 && s.spriteAt(0).anim == 3 && s.spriteAt(7).pat == 1 && s.spriteAt(10).anim == 4 && s.spriteAt(14).anim == 4 /* held over empty turn */
                        && s.spriteAt(15).anim == 4 && s.spriteAt(19).anim == 5 && s.spriteAt(34).anim == 5 && s.turnIndexAt(14) == 2 && s.turnIndexAt(34) == 3,
                "synthetic: sprite track " + s.sprite + "; held between events; turnIndexAt ok");
        SkillSeq one = SkillSeq.turn(t, 0, 3);
        check(one.total == 20 && one.turnStart[0] == 0 && one.events.size() == 2 && one.spriteAt(0) == null && one.spriteAt(5).anim == 5 && one.global(3, 4) == 4 && s.global(3, 4) == 19,
                "synthetic: turn mode = that turn only from frame 0; global(3,4): turn " + one.global(3, 4) + " / all " + s.global(3, 4));
    }

    static void legends(Fields f) {
        Fields.Legend atk = f.classes.get("CEventHit").field("attackTargetType").legend();
        Fields.Legend val = f.classes.get("CEventVal").field("int1").legend();
        Fields.Legend attr = f.classes.get("CEventSpriteAttr").field("int1").legend();
        Fields.Legend yure = f.classes.get("CEventSpriteYure").field("vibrationAreaType").legend();
        Fields.Legend hb = f.classes.get("CEventHit").field("int1").legend();
        Fields.Legend eff = f.classes.get("CEventEffect").field("int1").legend();
        Fields.Legend flg = f.classes.get("CEventHit").field("int2").legend();
        Fields.Field sw = null;
        for (Fields.Field x : f.mekWeapon) if (x.key.equals("switchToMekNo")) sw = x;
        check(atk.bits && atk.describe(6).equals("味方 + 自分") && atk.describe(1).equals("敵") && !atk.guessed,
                "legend bitmask 攻撃グループ: 6 = " + atk.describe(6) + " | " + atk.legendText());
        check(val.values.size() >= 2 && val.values.get(0).equals("直値") && !val.guessed && f.classes.get("CEventVal").field("int1").enumLabels().size() >= 2,
                "legend enum 開始値タイプ: " + val.legendText());
        check(attr.values.get(9) != null && attr.values.get(9).startsWith("加算") && attr.guessed, "legend 'N名前' list 描画タイプ (推定): " + attr.legendText());
        check(yure.values.size() == 8 && yure.guessed && yure.note.contains("推定"), "legend positional list 振動タイプ (推定, 並び順): " + yure.legendText());
        check(hb.values.size() == 2 && hb.guessed, "legend bool ヒットカウンタ初期化: " + hb.legendText());
        check(eff.values.isEmpty() && eff.note.contains("未解析"), "legend enum without meanings says so: " + eff.note);
        check(flg.bits && flg.values.isEmpty() && flg.note.contains("未解析"), "legend bitmask without meanings: " + flg.note);
        check(sw != null && sw.legend().special && "なし".equals(sw.legend().describe(-1)), "legend special value 機体切替 -1 = " + (sw == null ? "-" : sw.legend().describe(-1)));
        String txt = Form.LegendHint.text(atk, com.fasterxml.jackson.databind.node.IntNode.valueOf(3));
        check(txt.startsWith("現在 3 = 敵 + 味方") && txt.contains("bit2(4)=自分"), "legend hint text: " + txt);
    }

    static Project.Mech tooru(Project p) {
        for (Project.Mech m : p.mechs) if (m.mekFile != null && m.mekFile.toLowerCase().startsWith("tooru")) return m;
        return null;
    }

    static void realData(Project p) throws Exception {
        int skills = 0, events = 0, bad = 0, turnsBad = 0;
        List<String> why = new ArrayList<>();
        List<Project.Mech> ms = new ArrayList<>(List.of(p.mechs.get(0), p.mechs.get(7)));
        Project.Mech t = tooru(p);
        if (t != null) ms.add(t);
        for (Project.Mech m : ms) {
            JsonNode waz = p.doc(p.wazFile(m.wazIdx)).tree;
            for (int si = 0; si < waz.path("skillList").size(); si++) {
                SkillSeq s = SkillSeq.all(waz, si);
                int nt = waz.path("skillList").get(si).path("phasesInfo").size();
                int off = 0;
                TreeSet<Integer> slots = new TreeSet<>();
                for (int k = 0; k < nt; k++) {
                    if (s.turnStart[k] != off) bad++;
                    off += SkillSeq.displayLength(WazNav.turnLength(waz, si, k));
                    for (WazNav.Ev e : WazNav.events(waz, si, k)) slots.add(e.slot);
                    SkillSeq one = SkillSeq.turn(waz, si, k);
                    if (one.events.size() != WazNav.events(waz, si, k).size() || one.total != s.turnLen[k]) turnsBad++;
                }
                if (s.total != off || !s.slots.equals(new ArrayList<>(slots)) || s.events.size() != WazNav.events(waz, si, -1).size()) { bad++; if (why.size() < 3) why.add(m.mekFile + " 技" + si); }
                for (SkillSeq.Placed pl : s.events) if (pl.gStart != s.turnStart[pl.ev.turn] + pl.ev.start()) bad++;
                for (int g = 0; g < s.total; g += 3) {
                    SkillSeq.SpriteSeg sg = s.spriteAt(g);
                    if (sg != null && (sg.gStart > g)) bad++;
                }
                skills++; events += s.events.size();
            }
        }
        check(bad == 0 && turnsBad == 0 && skills > 500, "real data (" + ms.size() + " mechs incl. " + (t == null ? "no Tooru" : t.mekFile) + "): " + skills + " skills / " + events
                + " events laid out; offsets = cumulative turn lengths, rows = slot union, turn mode consistent; bad " + bad + "/" + turnsBad + " " + why);
    }

    /** Tooru skill with the most turns among its weapons (falls back to Kou). */
    static int[] pickSkill(Project p, Project.Mech m) throws Exception {
        FileDoc mek = p.doc(m.mekFile), waz = p.doc(p.wazFile(m.wazIdx));
        int best = -1, bt = 0;
        Iterator<JsonNode> it = mek.tree.path("mekWeaponInfoMap").elements();
        while (it.hasNext()) {
            int si = it.next().path("wazSequence").asInt();
            int nt = waz.tree.path("skillList").path(si).path("phasesInfo").size();
            if (nt > bt && SkillSeq.all(waz.tree, si).sprite.size() > 1) { bt = nt; best = si; }
        }
        return new int[]{best, bt};
    }

    static void ui(Project p) throws Exception {
        Project.Mech m = tooru(p);
        if (m == null) m = p.mechs.get(0);
        final Project.Mech mech = m;
        FileDoc waz = p.doc(p.wazFile(m.wazIdx));
        int[] pk = pickSkill(p, m);
        int si = pk[0];
        String[] res = {""};
        boolean[] ok = {true};
        SwingUtilities.invokeAndWait(() -> {
            try {
                Timeline tl = new Timeline(p);
                tl.showSkill(waz, si, mech.spmIdx, null);
                SkillSeq s = tl.seq();
                Dimension d = tl.getPreferredSize();
                tl.setSize(d);
                BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
                tl.paint(img.getGraphics());
                // one click on the last event of the last turn selects exactly that event
                SkillSeq.Placed last = s.events.get(s.events.size() - 1);
                String[] got = {null};
                int[] turnGot = {-1};
                int[][] slotGot = {null};
                java.lang.reflect.Field fs = Timeline.class.getDeclaredField("onSelect"); fs.setAccessible(true);
                fs.set(tl, (java.util.function.Consumer<WazNav.Ev>) e -> got[0] = e.ptr);
                java.lang.reflect.Field ft = Timeline.class.getDeclaredField("onTurn"); ft.setAccessible(true);
                ft.set(tl, (java.util.function.BiConsumer<Integer, Integer>) (a, b) -> turnGot[0] = b);
                java.lang.reflect.Field fl = Timeline.class.getDeclaredField("onSlot"); fl.setAccessible(true);
                fl.set(tl, (java.util.function.Consumer<int[]>) a -> slotGot[0] = a);
                Point c = tl.centerOf(last.ev.ptr);
                tl.click(c.x, c.y);
                ok[0] &= last.ev.ptr.equals(got[0]);
                int lt = s.turns.length - 1;
                tl.click(180 + (s.turnStart[lt] * 6) + 2, 5);   // v5 geometry: LEFT 180, ROW 20, TOP 36
                ok[0] &= turnGot[0] == s.turns[lt];
                tl.click(10, 36 + 20 * (s.slots.size() - 1) + 10);
                ok[0] &= slotGot[0] != null && slotGot[0][0] == si;
                ok[0] &= d.width == 180 + Math.max(10, s.total + 2) * 6 + 20; // not in a viewport -> 6 px/frame
                res[0] = "skill " + si + " (" + s.turns.length + " turns, " + s.total + "F, rows " + s.slots.size() + "): click last event " + (last.ev.ptr.equals(got[0]))
                        + ", header → turn " + turnGot[0] + ", row label → " + Arrays.toString(slotGot[0]);
                // preview of the concatenated turns
                SpmPreview pv = new SpmPreview(p);
                pv.setSize(300, 280);
                ok[0] &= pv.dir() == 2 && pv.showHit();
                pv.showSequence(SkillSeq.all(waz.tree, si), mech.spmIdx, null);
                int drawn = 0, segs = 0;
                for (SkillSeq.SpriteSeg sg : pv.sequence().sprite) {
                    pv.setGlobalFrame(sg.gStart);
                    if (pv.sequence().spriteAt(pv.globalFrame()) != sg && sg.gStart != sg.gEnd) continue;
                    segs++;
                    BufferedImage im = new BufferedImage(300, 280, BufferedImage.TYPE_INT_RGB);
                    pv.paint(im.getGraphics());
                    int bg = new Color(52, 54, 62).getRGB(), n = 0;
                    for (int y = 40; y < 280; y += 2) for (int x = 0; x < 300; x += 2) if (im.getRGB(x, y) != bg) n++;
                    if (n > 30) drawn++;
                }
                String fn = pv.segFile(pv.sequence().sprite.get(0));
                boolean spmThere = fn != null && p.spm(fn) != null;
                ok[0] &= !spmThere || drawn == segs;
                res[0] += "; preview " + segs + " sprite segments over " + pv.sequence().total + "F, drawn " + drawn + (spmThere ? "" : " (spm absent: " + fn + ")");
                // turn page preview = that turn only
                JComponent page = Editors.skill(new Form.Ctx(p, new UiSmokeTest.NavStub()), waz, mech.wazIdx, si, mech, 1);
                SpmPreview tp = ScreenshotTool.find(page, SpmPreview.class);
                ok[0] &= tp != null && tp.sequence().turnFilter == 1 && tp.sequence().total == s.turnLen[1];
                JComponent all = Editors.skill(new Form.Ctx(p, new UiSmokeTest.NavStub()), waz, mech.wazIdx, si, mech, -1);
                SpmPreview ap = ScreenshotTool.find(all, SpmPreview.class);
                ok[0] &= ap != null && ap.sequence().isAll() && ap.sequence().total == s.total && ap.dir() == 2 && ap.showHit();
                // event page: legend + raw names inline
                WazNav.Ev hit = null;
                for (WazNav.Ev e : WazNav.events(waz.tree, si, -1)) if (e.typeId() == 16) { hit = e; break; }
                if (hit == null) hit = WazNav.firstHit(waz.tree, 12);
                JComponent ep = Editors.event(new Form.Ctx(p, new UiSmokeTest.NavStub()), waz, mech.wazIdx, mech, hit.ptr);
                List<String> hints = new ArrayList<>(), raws = new ArrayList<>();
                collect(ep, hints, raws);
                boolean atk = hints.stream().anyMatch(h -> h.contains("bit0(1)=敵"));
                boolean raw = raws.contains("hitCount") && raws.contains("int1");
                ok[0] &= atk && raw;
                res[0] += "; Hit page: legend lines " + hints.size() + " (攻撃グループ " + atk + "), raw names shown " + raws.size() + " (hitCount/int1 " + raw + ")";
            } catch (Exception e) { ok[0] = false; res[0] += " EXC " + e; e.printStackTrace(); }
        });
        check(ok[0] && si >= 0, "UI: " + res[0]);
    }

    static void collect(Container c, List<String> hints, List<String> raws) {
        for (Component k : c.getComponents()) {
            if (k instanceof Form.LegendHint) hints.add(((Form.LegendHint) k).getText());
            if (k instanceof JLabel && ((JLabel) k).getClientProperty("rawKey") != null) raws.add(String.valueOf(((JLabel) k).getClientProperty("rawKey")));
            if (k instanceof JTabbedPane) { JTabbedPane t = (JTabbedPane) k; for (int i = 0; i < t.getTabCount(); i++) if (t.getComponentAt(i) instanceof Container) collect((Container) t.getComponentAt(i), hints, raws); }
            else if (k instanceof Container) collect((Container) k, hints, raws);
        }
    }
}
