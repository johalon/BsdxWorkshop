package bsdx.workshop;

import bsdx.workshop.data.*;
import bsdx.workshop.ui.*;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Headless construction of every property page / tree / timeline over real data (no JFrame needed). */
public class UiSmokeTest {
    static int pass, fail;
    static List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; failures.add(what); System.out.println("  FAIL " + what); }
    }

    static final class NavStub implements Form.Nav {
        final List<String> log = new ArrayList<>();
        public void openSkill(int w, int s) { log.add("open " + w + ":" + s); }
        public void status(String m) { log.add(m); }
    }

    static void run(Codec codec, Fields fields, Path stage, Path overlay) throws Exception {
        System.out.println("[UI components (headless=" + GraphicsEnvironment.isHeadless() + ")]");
        Project p = Loader.open(codec, fields, stage, true, Files.isRegularFile(overlay) ? overlay : null, null);
        p.buildRefIndex();
        NavStub nav = new NavStub();
        int[] built = {0}, errors = {0};
        Set<Integer> types = new TreeSet<>();
        List<String> errs = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Project.Mech m : List.of(p.mechs.get(0), p.mechs.get(9), p.mechs.get(10))) {
                    if (m.mekFile == null) continue;
                    FileDoc mek = p.doc(m.mekFile), waz = p.doc(p.wazFile(m.wazIdx));
                    Editors.mekBasic(new Form.Ctx(p, nav), mek);
                    built[0]++;
                    Iterator<String> it = mek.tree.path("mekWeaponInfoMap").fieldNames();
                    int wn = 0;
                    while (it.hasNext() && wn++ < 30) { Editors.weapon(new Form.Ctx(p, nav), mek, it.next(), waz, m.wazIdx); built[0]++; }
                    SkillTree st = new SkillTree(p);
                    st.setWaz(waz, m.wazIdx, m, "test");
                    Timeline tl = new Timeline(p);
                    JsonNode sl = waz.tree.path("skillList");
                    for (int si = 0; si < Math.min(sl.size(), 80); si++) {
                        Editors.skill(new Form.Ctx(p, nav), waz, m.wazIdx, si, m);
                        built[0]++;
                        Set<Integer> seenHere = new HashSet<>();
                        for (WazNav.Ev e : WazNav.events(waz.tree, si, -1)) {
                            if (!seenHere.add(e.typeId() * 100 + e.slot) && e.typeId() != 16) continue;
                            try {
                                Editors.event(new Form.Ctx(p, nav), waz, m.wazIdx, m, e.ptr);
                                types.add(e.typeId());
                                built[0]++;
                            } catch (Exception ex) {
                                errors[0]++;
                                if (errs.size() < 5) { errs.add(m.mekFile + " " + e.ptr + " : " + ex); ex.printStackTrace(); }
                            }
                        }
                        tl.show(waz, si, 0, m.spmIdx, null);
                        tl.setSize(tl.getPreferredSize());
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, tl.getWidth()), Math.max(1, tl.getHeight()), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        tl.paint(img.getGraphics());
                    }
                    st.select(12, 0, 0, 0);
                }
            } catch (Exception ex) {
                errors[0]++;
                errs.add(ex.toString());
                ex.printStackTrace();
            }
        });
        check(errors[0] == 0 && built[0] > 500, "property pages built: " + built[0] + ", event types covered " + types.size() + " " + types + ", errors " + errors[0] + " " + errs);
        // simulate typing a new damage into the generated form and committing with Enter
        Project.Mech kou = p.mechs.get(0);
        FileDoc waz = p.doc(p.wazFile(kou.wazIdx));
        WazNav.Ev hit = WazNav.firstHit(waz.tree, 12);
        String dp = WazNav.damagePtr(waz.tree, hit.ptr);
        boolean[] ok = {false};
        SwingUtilities.invokeAndWait(() -> {
            Form.Ctx ctx = new Form.Ctx(p, nav);
            JComponent page = Editors.event(ctx, waz, kou.wazIdx, kou, hit.ptr);
            Form.IntField f = find(page, Form.IntField.class, x -> x.getToolTipText() != null && x.getToolTipText().equals(dp));
            if (f != null) {
                f.setText("88");
                f.postActionEvent();
                ok[0] = waz.tree.at(dp).asInt() == 88 && p.undo.canUndo();
                p.undo.undo();
                ctx.reloadAll();
                ok[0] &= f.getText().equals("50");
            }
        });
        check(ok[0], "damage field in the generated Hit page: type 88 + Enter edits the tree; undo reloads the field to 50");
        // shared bullet warning: a Tama01 skill used by several mechs
        FileDoc tama = p.doc(p.wazFile(1));
        String[] warn = {null};
        int sk = -1;
        for (int i = 0; i < tama.tree.path("skillList").size() && sk < 0; i++) {
            Set<Integer> users = new HashSet<>();
            for (RefIndex.Ref r : p.refIndex().referrers(1, i)) if (p.mechOfWaz(r.srcWaz) != null) users.add(r.srcWaz);
            if (users.size() >= 2) sk = i;
        }
        final int fsk = sk;
        SwingUtilities.invokeAndWait(() -> {
            JComponent page = Editors.skill(new Form.Ctx(p, nav), tama, 1, fsk, null);
            JTextArea a = find(page, JTextArea.class, x -> x.getText().startsWith("⚠"));
            warn[0] = a == null ? null : a.getText();
        });
        check(sk >= 0 && warn[0] != null, "shared bullet Tama01 skill " + sk + " shows the multi-mech warning: " + (warn[0] == null ? "-" : warn[0].split("\n")[0]));
        // ---- v2: AI editor, weapon page skill dropdown, SE play button
        String[] aiRes = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                int pages = 0;
                for (Project.Mech m : List.of(p.mechs.get(0), p.mechs.get(7), p.mechs.get(10))) {
                    if (m.mekFile == null) continue;
                    FileDoc mek = p.doc(m.mekFile);
                    Form.Ctx c = new Form.Ctx(p, nav);
                    AiEditor ae = new AiEditor(c, m, mek, r -> {});
                    int n = ae.table().getRowCount();
                    for (int j = 0; j < Math.min(n, 40); j++) { ae.selectEvent(j); pages++; }
                }
                // attack weapon dropdown edits the tree
                Project.Mech aki = p.mechs.get(7);
                FileDoc mek = p.doc(aki.mekFile);
                Form.Ctx c = new Form.Ctx(p, nav);
                AiEditor ae = new AiEditor(c, aki, mek, r -> {});
                int j = -1;
                JsonNode ev = mek.tree.path("mekAiInfoList").get(0).path("cpuEventList");
                for (int i = 0; i < ev.size(); i++) if (ev.get(i).path("type").asInt() == 2) { j = i; break; }
                ae.selectEvent(j);
                String ptr = "/mekAiInfoList/0/cpuEventList/" + j + "/mekWeaponInfoMapNo";
                Form.IntCombo wc = find(ae, Form.IntCombo.class, x -> x.ptr.equals(ptr));
                int before = mek.tree.at(ptr).asInt();
                int target = before == 3 ? 4 : 3;
                for (int i = 0; i < wc.getItemCount(); i++) if (wc.getItemAt(i).value == target) wc.setSelectedIndex(i);
                boolean ok2 = mek.tree.at(ptr).asInt() == target && wc.getItemCount() == mek.tree.path("mekWeaponInfoMap").size();
                p.undo.undo();
                ok2 &= mek.tree.at(ptr).asInt() == before;
                aiRes[0] = (ok2 ? "ok" : "bad") + " pages " + pages + ", weapon items " + wc.getItemCount() + " (" + wc.getItemAt(0) + " …)";
                // weapon page: wazSequence as skill dropdown
                FileDoc awaz = p.doc(p.wazFile(aki.wazIdx));
                JComponent wp = Editors.weapon(new Form.Ctx(p, nav), mek, "0", awaz, aki.wazIdx);
                Form.IntCombo sc = find(wp, Form.IntCombo.class, x -> x.ptr.endsWith("/wazSequence"));
                aiRes[0] += sc != null && sc.getItemCount() == awaz.tree.path("skillList").size() ? "; skill dropdown " + sc.getItemCount() : "; NO skill dropdown";
                // SE play button exists on SE candidate rows
                WazNav.Ev se = null;
                for (int s = 0; s < awaz.tree.path("skillList").size() && se == null; s++) for (WazNav.Ev e : WazNav.events(awaz.tree, s, -1)) if (e.typeId() == 23) { se = e; break; }
                JComponent sp = Editors.event(new Form.Ctx(p, nav), awaz, aki.wazIdx, aki, se.ptr);
                JButton play = find(sp, JButton.class, x -> "▶".equals(x.getText()));
                aiRes[0] += play != null ? "; 試聴 button" : "; NO 試聴";
                if (play != null) play.doClick();   // no Se.pac in the cloud -> status message, no exception
            } catch (Exception ex) { aiRes[0] = "exception " + ex; ex.printStackTrace(); }
        });
        Thread.sleep(300);
        check(aiRes[0].startsWith("ok") && aiRes[0].contains("skill dropdown") && aiRes[0].contains("試聴 button"), "v2 pages: AI editor for 3 mechs, attack weapon dropdown edits + undo, weapon skill dropdown, SE 試聴: " + aiRes[0]
                + " / last status: " + (nav.log.isEmpty() ? "-" : nav.log.get(nav.log.size() - 1)));
        p.fs.close();
    }

    @SuppressWarnings("unchecked")
    static <T> T find(Container c, Class<T> cls, java.util.function.Predicate<T> pred) {
        for (Component k : c.getComponents()) {
            if (cls.isInstance(k) && pred.test((T) k)) return (T) k;
            if (k instanceof Container) { T r = find((Container) k, cls, pred); if (r != null) return r; }
        }
        return null;
    }
}
