package bsdx.workshop;

import bsdx.workshop.data.*;
import bsdx.workshop.ui.MainFrame;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Renders the main window under Xvfb for layout checks: build_gui.sh shot [outDir] */
public class ScreenshotTool {
    public static void main(String[] a) throws Exception {
        Path out = Path.of(a.length > 0 ? a[0] : "/home/claude/proj/work/gui_test/shots");
        Files.createDirectories(out);
        Path stage = Path.of(System.getProperty("stage", "game"));
        Path ov = Path.of(System.getProperty("overlay", "/home/claude/proj/mods/phase3c_merged/Update3.pac"));
        if (!Files.exists(ov)) ov = Path.of("/home/claude/proj/mods/phase3b_tooru/Update3.pac");
        Settings s = new Settings(null);
        s.gameDir = stage.toString();
        s.overlay = ov.toString();
        s.projectRoot = "work/gui_test/PROJECT";
        s.exportDir = s.projectRoot + "/mods/gui_out";
        Codec codec = new Codec();
        Fields fields = Fields.load(codec.mapper);
        Project p = Loader.open(codec, fields, stage, true, ov, null);
        p.buildRefIndex();
        MainFrame[] f = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            Main.installLookAndFeel();
            f[0] = new MainFrame(s, codec, fields);
            f[0].setSize(1600, 1000);
            f[0].setLocation(0, 0);
            f[0].setVisible(true);
            f[0].setProject(p);
        });
        Robot robot = new Robot();
        // 1) Kou: weapon 0 -> hit event
        SwingUtilities.invokeAndWait(() -> {
            try {
                f[0].selectMechNow(p.mechs.get(0));
                f[0].selectWeaponRow(0);
                WazNav.Ev hit = WazNav.firstHit(p.doc(p.wazFile(p.mechs.get(0).wazIdx)).tree, 12);
                f[0].tree().select(12, hit.turn, hit.unitIdx, hit.objIdx);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        shot(robot, f[0], out.resolve("main_kou_hit.png"));
        // 2) ported mech (overlay): first sprite event of its first weapon skill
        Project.Mech port = null;
        for (Project.Mech m : p.mechs) if (m.mekFile != null && p.fs.layerOf(m.mekFile) == p.fs.getOverlay() && m.mekFile.toLowerCase().startsWith("tooru")) port = m;
        if (port == null) for (Project.Mech m : p.mechs) if (m.mekFile != null && p.fs.layerOf(m.mekFile) == p.fs.getOverlay()) { port = m; break; }
        if (port != null) {
            final Project.Mech pm = port;
            SwingUtilities.invokeAndWait(() -> {
                try {
                    f[0].selectMechNow(pm);
                    FileDoc mek = p.doc(pm.mekFile);
                    int si = mek.tree.path("mekWeaponInfoMap").path("0").path("wazSequence").asInt();
                    FileDoc waz = p.doc(p.wazFile(pm.wazIdx));
                    for (WazNav.Ev e : WazNav.events(waz.tree, si, -1))
                        if (e.typeId() == 13) { f[0].tree().select(si, e.turn, e.unitIdx, e.objIdx); break; }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.sleep(800);
            shot(robot, f[0], out.resolve("main_port_sprite.png"));
        }
        // 3) mech stats page
        SwingUtilities.invokeAndWait(() -> { try { f[0].selectMechNow(p.mechs.get(0)); } catch (Exception e) { throw new RuntimeException(e); } });
        shot(robot, f[0], out.resolve("main_kou_stats.png"));
        // ---------------- v2 panels + UI flow checks
        Project.Mech aki = p.mechs.get(7);
        SwingUtilities.invokeAndWait(() -> { try { f[0].selectMechNow(aki); f[0].openAiTabForTest(); } catch (Exception e) { throw new RuntimeException(e); } });
        SwingUtilities.invokeAndWait(() -> {
            bsdx.workshop.ui.AiEditor ae = find(f[0].props(), bsdx.workshop.ui.AiEditor.class);
            if (ae != null) {
                JsonNodeHolder.j = -1;
                com.fasterxml.jackson.databind.JsonNode ev = p.docIfLoaded(aki.mekFile).tree.path("mekAiInfoList").get(0).path("cpuEventList");
                for (int i = 0; i < ev.size(); i++) if (ev.get(i).path("type").asInt() == 2) { JsonNodeHolder.j = i; break; }
                ae.selectEvent(JsonNodeHolder.j);
            }
            flow(ae != null, "AI tab shows the AI editor");
        });
        shot(robot, f[0], out.resolve("v2_ai_editor.png"));
        SwingUtilities.invokeAndWait(() -> { f[0].weaponTable().clearSelection(); f[0].selectWeaponRow(0); });
        shot(robot, f[0], out.resolve("v2_weapon.png"));
        // weapon duplicate through the window (button path), then undo
        int nw0 = p.docIfLoaded(aki.mekFile).tree.path("mekWeaponInfoMap").size();
        SwingUtilities.invokeAndWait(() -> f[0].weaponActionForTest("dupEnd"));
        int nw1 = p.docIfLoaded(aki.mekFile).tree.path("mekWeaponInfoMap").size();
        SwingUtilities.invokeAndWait(() -> flow(nw1 == nw0 + 1 && f[0].weaponTable().getRowCount() == nw0 + 1 && f[0].weaponTable().getSelectedRow() == nw0, "weapon 複製 button: table " + nw0 + "→" + f[0].weaponTable().getRowCount() + " rows, new row selected"));
        shot(robot, f[0], out.resolve("v2_weapon_dup.png"));
        SwingUtilities.invokeAndWait(() -> { p.undo.undo(); flow(f[0].weaponTable().getRowCount() == nw0, "undo: weapon table back to " + nw0); });
        // skill duplicate through the tree (Ctrl+D path) on Aki's waz
        FileDoc aw = p.doc(p.wazFile(aki.wazIdx));
        int ns0 = aw.tree.path("skillList").size();
        SwingUtilities.invokeAndWait(() -> { f[0].tree().select(10, -1, -1, -1); f[0].structOnTreeForTest("dup"); });
        SwingUtilities.invokeAndWait(() -> {
            bsdx.workshop.ui.SkillTree.N n = f[0].tree().selected();
            flow(aw.tree.path("skillList").size() == ns0 + 1 && n != null && n.si == 11, "tree Ctrl+D on skill 10: skills " + ns0 + "→" + aw.tree.path("skillList").size() + ", selection on the copy (技 " + (n == null ? "-" : n.si) + ")");
            f[0].tree().select(11, 0, -1, -1);
        });
        SwingUtilities.invokeAndWait(() -> f[0].treePopupForTest());
        Thread.sleep(300);
        shotScreen(robot, out.resolve("v2_tree_menu.png"));
        SwingUtilities.invokeAndWait(() -> { for (MenuElement m : MenuSelectionManager.defaultManager().getSelectedPath()) {} MenuSelectionManager.defaultManager().clearSelectedPath(); });
        // diff dialog (modal) -> capture -> close
        SwingUtilities.invokeLater(() -> f[0].showDiffForTest());
        Thread.sleep(1200);
        shotScreen(robot, out.resolve("v2_diff.png"));
        closeDialogs();
        SwingUtilities.invokeAndWait(() -> { while (p.undo.canUndo()) p.undo.undo(); flow(aw.tree.path("skillList").size() == ns0 && p.dirtyDocs().isEmpty(), "undo all: skills back to " + ns0 + ", nothing dirty"); });
        // search + help
        SwingUtilities.invokeAndWait(() -> f[0].searchForTest("ライフル"));
        Thread.sleep(500);
        shotScreen(robot, out.resolve("v2_search.png"));
        closeDialogs();
        SwingUtilities.invokeAndWait(() -> f[0].showHelpForTest());
        Thread.sleep(500);
        shotScreen(robot, out.resolve("v2_help.png"));
        closeDialogs();
        // ---------------- v3: skill-level timeline (all turns) + concatenated sprite preview, legends
        if (port != null) {
            final Project.Mech pm = port;
            FileDoc tw = p.doc(p.wazFile(pm.wazIdx));
            int[] pk = TimelineTest.pickSkill(p, pm);
            int ssi = pk[0];
            SwingUtilities.invokeAndWait(() -> { try { f[0].selectMechNow(pm); f[0].tree().select(ssi, -1, -1, -1); } catch (Exception e) { throw new RuntimeException(e); } });
            Thread.sleep(1500);
            SwingUtilities.invokeAndWait(() -> {
                bsdx.workshop.ui.Timeline tl = f[0].timeline();
                bsdx.workshop.ui.SpmPreview pv = find(f[0].overview(), bsdx.workshop.ui.SpmPreview.class);
                flow(tl.isSkillMode() && tl.seq().turns.length == pk[1], "v3 skill " + ssi + " selected: timeline shows all " + tl.seq().turns.length + " turns (" + tl.seq().total + "F)");
                flow(pv != null && pv.sequence() != null && pv.sequence().isAll() && pv.dir() == 2 && pv.showHit() && pv.isPlaying(),
                        "v3 skill page has the concatenated sprite preview (dir 2, hit ON, playing; frame " + (pv == null ? -1 : pv.globalFrame()) + ")");
            });
            shot(robot, f[0], out.resolve("v3_skill_timeline.png"));
            // one click on a bar in the 3rd turn (or last) -> that event, timeline stays in the all-turns layout
            String[] target = {null};
            SwingUtilities.invokeAndWait(() -> {
                bsdx.workshop.ui.Timeline tl = f[0].timeline();
                SkillSeq sq = tl.seq();
                int want = Math.min(2, sq.turns.length - 1);
                for (SkillSeq.Placed pl : sq.events) if (pl.ev.turn == want && pl.ev.typeId() == 16) { target[0] = pl.ev.ptr; break; }
                if (target[0] == null) for (SkillSeq.Placed pl : sq.events) if (pl.ev.turn == want) { target[0] = pl.ev.ptr; break; }
                Point c = tl.centerOf(target[0]);
                tl.click(c.x, c.y);
            });
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> {
                bsdx.workshop.ui.SkillTree.N n = f[0].tree().selected();
                flow(n != null && n.kind == 4 && target[0].equals(n.ptr) && f[0].timeline().isSkillMode() && target[0].equals(f[0].timeline().selectedPtr()),
                        "v3 one click on a timeline bar selects " + target[0] + " in the tree; timeline keeps all turns");
            });
            shot(robot, f[0], out.resolve("v3_skill_click_event.png"));
            // turn selected -> that turn's preview; timeline = that turn
            SwingUtilities.invokeAndWait(() -> f[0].tree().select(ssi, 1, -1, -1));
            Thread.sleep(1200);
            SwingUtilities.invokeAndWait(() -> {
                bsdx.workshop.ui.SpmPreview pv = find(f[0].overview(), bsdx.workshop.ui.SpmPreview.class);
                flow(!f[0].timeline().isSkillMode() && pv != null && pv.sequence() != null && pv.sequence().turnFilter == 1, "v3 turn 1 selected: timeline + preview show turn 1 only");
            });
            shot(robot, f[0], out.resolve("v3_turn_preview.png"));
            // a Hit event page: value legends + raw names
            SwingUtilities.invokeAndWait(() -> {
                WazNav.Ev h = null;
                for (WazNav.Ev e : WazNav.events(tw.tree, ssi, -1)) if (e.typeId() == 16) { h = e; break; }
                if (h != null) f[0].tree().select(ssi, h.turn, h.unitIdx, h.objIdx);
            });
            shot(robot, f[0], out.resolve("v3_hit_legend.png"));
        }
        // ---------------- v5: outline / overview links / bullet jump / back & forward
        if (port != null) {
            final Project.Mech pm = port;
            SwingUtilities.invokeAndWait(() -> { try { f[0].selectMechNow(pm); } catch (Exception e) { throw new RuntimeException(e); } });
            shot(robot, f[0], out.resolve("v5_mech_page.png"));
            // a weapon whose skill spawns something in another waz
            FileDoc mek = p.doc(pm.mekFile), tw = p.doc(p.wazFile(pm.wazIdx));
            String[] wk = {null}; int[][] ref = {null};
            for (java.util.Iterator<String> it = mek.tree.path("mekWeaponInfoMap").fieldNames(); it.hasNext() && wk[0] == null; ) {
                String k = it.next();
                int si = mek.tree.path("mekWeaponInfoMap").path(k).path("wazSequence").asInt();
                for (WazNav.Ev e : WazNav.events(tw.tree, si, -1)) {
                    if (e.typeId() == 16) continue;
                    int[] r = bsdx.workshop.ui.Timeline.refOf(e.node);
                    if (r != null && r[0] >= 0 && r[0] != pm.wazIdx) { wk[0] = k; ref[0] = r; break; }
                }
            }
            if (wk[0] != null) {
                int n0 = f[0].historySize();
                SwingUtilities.invokeAndWait(() -> f[0].outline().select(bsdx.workshop.ui.Outline.WEAPON, null, -1));
                SwingUtilities.invokeAndWait(() -> { f[0].outline().select(bsdx.workshop.ui.Outline.WEAPON, wk[0], -1); });
                // select through the outline (as a click would)
                SwingUtilities.invokeAndWait(() -> { javax.swing.JTree t = find(f[0].outline(), javax.swing.JTree.class); javax.swing.tree.TreePath tp = t.getSelectionPath(); t.clearSelection(); t.setSelectionPath(tp); });
                Thread.sleep(800);
                shot(robot, f[0], out.resolve("v5_weapon_overview.png"));
                SwingUtilities.invokeAndWait(() -> flow(f[0].overview().showsSkill(tw, mek.tree.path("mekWeaponInfoMap").path(wk[0]).path("wazSequence").asInt(), -1), "v5 outline weapon " + wk[0] + " -> overview shows its skill"));
                // double click on the spawning bar -> the spawned skill (other waz)
                SwingUtilities.invokeAndWait(() -> {
                    bsdx.workshop.ui.Timeline tl = f[0].timeline();
                    for (SkillSeq.Placed pl : tl.seq().events) {
                        int[] r = bsdx.workshop.ui.Timeline.refOf(pl.ev.node);
                        if (pl.ev.typeId() != 16 && r != null && r[0] == ref[0][0] && r[1] == ref[0][1]) {
                            Point c = tl.centerOf(pl.ev.ptr);
                            java.awt.event.MouseEvent me = new java.awt.event.MouseEvent(tl, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, c.x, c.y, 2, false, java.awt.event.MouseEvent.BUTTON1);
                            for (java.awt.event.MouseListener l : tl.getMouseListeners()) l.mousePressed(me);
                            break;
                        }
                    }
                });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> { bsdx.workshop.ui.SkillTree.N n = f[0].tree().selected(); flow(n != null && n.kind == 1 && n.si == ref[0][1] && f[0].project().wazFile(ref[0][0]).equalsIgnoreCase(f[0].tree().docName()), "v5 double click on the bullet bar opens waz " + ref[0][0] + " skill " + ref[0][1]); });
                shot(robot, f[0], out.resolve("v5_bullet_skill.png"));
                SwingUtilities.invokeAndWait(() -> f[0].goBackForTest());
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> flow(f[0].outline().selected() != null && f[0].outline().selected().kind == bsdx.workshop.ui.Outline.WEAPON && wk[0].equals(f[0].outline().selected().key), "v5 back returns to weapon " + wk[0] + " of " + pm.mekName));
                shot(robot, f[0], out.resolve("v5_back.png"));
                SwingUtilities.invokeAndWait(() -> f[0].goForwardForTest());
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> { bsdx.workshop.ui.SkillTree.N n = f[0].tree().selected(); flow(n != null && n.si == ref[0][1], "v5 forward goes to the bullet skill again"); });
            } else flow(false, "v5: no weapon spawning a shared skill found");
        }
        // SE event of the ported mech (overlay ogg) -> F9 play path (no audio device under Xvfb is reported, not thrown)
        System.out.println("UI flow: " + flowOk + " ok, " + flowBad + " failed");
        System.out.println("screenshots in " + out);
        System.exit(flowBad == 0 ? 0 : 1);
    }

    static final class JsonNodeHolder { static int j; }
    static int flowOk, flowBad;
    static void flow(boolean ok, String what) { if (ok) flowOk++; else flowBad++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }

    @SuppressWarnings("unchecked")
    static <T> T find(Container c, Class<T> cls) {
        for (Component k : c.getComponents()) {
            if (cls.isInstance(k)) return (T) k;
            if (k instanceof Container) { T r = find((Container) k, cls); if (r != null) return r; }
        }
        return null;
    }

    static void closeDialogs() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Window w : Window.getWindows()) if (w instanceof Dialog && w.isShowing()) w.dispose(); });
        Thread.sleep(200);
    }

    static void shotScreen(Robot r, Path file) throws Exception {
        Thread.sleep(400);
        Rectangle b = new Rectangle(0, 0, 1600, 1000);
        ImageIO.write(r.createScreenCapture(b), "png", file.toFile());
        System.out.println("wrote " + file);
    }

    static void shot(Robot r, JFrame f, Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> { f.validate(); f.repaint(); });
        Thread.sleep(700);
        Rectangle b = f.getBounds();
        BufferedImage img = r.createScreenCapture(b);
        ImageIO.write(img, "png", file.toFile());
        System.out.println("wrote " + file);
    }
}
