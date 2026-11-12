package bsdx.workshop.ui;

import bsdx.workshop.data.*;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * v5 centre panel. For a skill: what it is, which weapons / basic motions use it, its attacks, the skills it spawns
 * (bullets, effects, hit effects), its jump targets and who references it — every item a link — plus the sprite preview.
 * For a mech: the weapon list (weapon → skill → attack).
 */
public final class Overview extends JPanel {
    public interface Links {
        void openWeapon(String key);
        void openSkill(int wazIdx, int si);
        void openMechPage();
    }

    final Project p;
    final Links links;
    final JEditorPane info = new JEditorPane();
    final JPanel previewHolder = new JPanel(new BorderLayout());
    final JPanel body = new JPanel(new BorderLayout());
    final List<Runnable> actions = new ArrayList<>();
    // what is shown
    FileDoc waz, mek; int wazIdx = -1, si = -1, turn = -1; Project.Mech mech;
    boolean mechMode;
    Form.Ctx previewCtx;

    public Overview(Project p, Links links) {
        super(new BorderLayout());
        this.p = p; this.links = links;
        info.setContentType("text/html");
        info.setEditable(false);
        info.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        info.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        info.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            try { int i = Integer.parseInt(e.getDescription().substring(2)); if (i >= 0 && i < actions.size()) actions.get(i).run(); }
            catch (RuntimeException ignored) {}
        });
        JScrollPane sp = new JScrollPane(info);
        sp.setBorder(BorderFactory.createEmptyBorder());
        body.add(sp, BorderLayout.CENTER);
        previewHolder.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 8));
        add(body, BorderLayout.CENTER);
        add(previewHolder, BorderLayout.EAST);
        showText("機体を選んでください。");
    }

    String link(String text, Runnable r) {
        actions.add(r);
        return "<a href='a:" + (actions.size() - 1) + "'>" + Form.esc(text) + "</a>";
    }

    static String esc(String s) { return Form.esc(s == null ? "" : s); }

    static final String CSS = "<style>body{margin:0} td{padding:2px 10px 2px 0;vertical-align:top} .k{color:#6b7280;white-space:nowrap}"
            + " .t{font-size:1.25em;font-weight:bold} .s{color:#6b7280} .w{color:#b4471c} a{color:#2563c9}</style>";

    void setHtml(String html) {
        info.setText("<html><head>" + CSS + "</head><body>" + html + "</body></html>");
        info.setCaretPosition(0);
    }

    public void showText(String msg) {
        mechMode = false; waz = null; si = -1;
        actions.clear();
        setHtml("<p class='s'>" + esc(msg).replace("\n", "<br>") + "</p>");
        setPreview(null);
        body.removeAll();
        JScrollPane sp = new JScrollPane(info); sp.setBorder(BorderFactory.createEmptyBorder());
        body.add(sp, BorderLayout.CENTER);
        revalidate(); repaint();
    }

    void setPreview(JComponent c) {
        previewHolder.removeAll();
        if (c != null) previewHolder.add(c, BorderLayout.CENTER);
        previewHolder.setVisible(c != null);
        previewHolder.revalidate(); previewHolder.repaint();
    }

    /** Mech page: header + the weapon table (weapon → skill → attack) with its buttons. */
    public void showMech(Project.Mech m, FileDoc mek, FileDoc waz, JComponent weaponPane) {
        mechMode = true; this.mech = m; this.mek = mek; this.waz = waz; this.wazIdx = m.wazIdx; this.si = -1;
        actions.clear();
        setHtml("<div class='t'>" + esc(m.mekName) + "<span class='s'>　" + esc(m.pilot) + "</span></div>"
                + "<div class='s'>機体 " + m.id + "　" + esc(m.mekFile) + "（元: " + esc(mek.sourceLabel) + "）　waz " + (waz == null ? "-" : esc(waz.name))
                + "　武装 " + mek.tree.path("mekWeaponInfoMap").size() + "　技 " + (waz == null ? 0 : waz.tree.path("skillList").size()) + "</div>"
                + "<p class='s'>武装の行をクリックするとその武装と技へ移動します。右の欄で機体ステータスと AI 表を編集できます。</p>");
        setPreview(null);
        body.removeAll();
        body.add(info, BorderLayout.NORTH);
        body.add(weaponPane, BorderLayout.CENTER);
        revalidate(); repaint();
    }

    public boolean showsSkill(FileDoc waz, int si, int turn) { return !mechMode && this.waz == waz && this.si == si && this.turn == turn; }

    /** Skill page; the preview is rebuilt only when the skill / turn changes. */
    public void showSkill(Form.Ctx ctx, FileDoc waz, int wazIdx, int si, Project.Mech mech, FileDoc mek, int turn) {
        boolean same = showsSkill(waz, si, turn);
        mechMode = false;
        this.waz = waz; this.wazIdx = wazIdx; this.si = si; this.mech = mech; this.mek = mek; this.turn = turn;
        if (body.getComponentCount() != 1 || !(body.getComponent(0) instanceof JScrollPane)) {
            body.removeAll();
            JScrollPane sp = new JScrollPane(info); sp.setBorder(BorderFactory.createEmptyBorder());
            body.add(sp, BorderLayout.CENTER);
        }
        refreshInfo();
        if (!same) {
            previewCtx = ctx;
            JComponent pv = Editors.skillPreview(ctx, waz, si, mech, turn, 240, 240);
            if (pv != null) {
                JPanel box = new JPanel(new BorderLayout());
                JLabel cap = new JLabel(turn < 0 ? "スプライト（全ターン連結・ジャンプ無視）" : "スプライト（ターン " + turn + "）");
                cap.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
                box.add(cap, BorderLayout.NORTH);
                box.add(pv, BorderLayout.CENTER);
                setPreview(box);
            } else setPreview(null);
        }
        revalidate(); repaint();
    }

    /** Re-renders the text part (after an edit). */
    public void refreshInfo() {
        if (mechMode || waz == null || si < 0) return;
        if (si >= waz.tree.path("skillList").size()) { showText("技 " + si + " はもうありません。"); return; }
        actions.clear();
        StringBuilder h = new StringBuilder();
        JsonNode s = waz.tree.at(WazNav.skillPtr(si));
        JsonNode ph = s.path("phasesInfo");
        int total = 0; StringBuilder lens = new StringBuilder();
        for (int t = 0; t < ph.size(); t++) { int l = WazNav.turnLength(waz.tree, si, t); total += l; lens.append(t > 0 ? " / " : "").append(l); }
        h.append("<div class='t'>技 ").append(si).append("　").append(esc(WazNav.skillName(waz.tree, si))).append("</div>");
        h.append("<div class='s'>").append(esc(waz.name)).append("（").append(esc(p.wazLabel(wazIdx))).append("）　ターン ").append(ph.size())
                .append(ph.size() > 0 ? "　計 " + total + "F（" + lens + "）" : "").append(turn >= 0 ? "　表示中: ターン " + turn : "").append("</div>");
        Project.Mech owner = p.mechOfWaz(wazIdx);
        if (owner == null) {
            Set<Integer> mechs = new TreeSet<>();
            RefIndex ri = p.refIndex();
            if (ri != null) for (RefIndex.Ref r : ri.referrers(wazIdx, si)) if (p.mechOfWaz(r.srcWaz) != null) mechs.add(r.srcWaz);
            h.append("<p class='w'>⚠ 共通の技です。").append(mechs.isEmpty() ? "" : mechs.size() + " 機の waz から使われています。")
                    .append("ここでの変更はこの技を使うすべての機体に効きます。</p>");
        }
        h.append("<table>");
        // weapons / basic motions
        if (mek != null && owner != null && mech != null && mech.wazIdx == wazIdx) {
            List<String> ws = new ArrayList<>();
            for (String k : Outline.weaponKeys(mek)) {
                JsonNode w = mek.tree.path("mekWeaponInfoMap").path(k);
                if (w.path("wazSequence").asInt() != si) continue;
                int cat = w.path("weaponCategory").asInt();
                ws.add(link("武装 " + k + " " + w.path("weaponName").asText(), () -> links.openWeapon(k))
                        + "<span class='s'>（" + (cat == 0 ? "通常" : cat == 1 ? "FC" : cat == 2 ? "INIT" : "" + cat) + "・" + (w.path("weaponType").asInt() == 0 ? "格闘" : "射撃")
                        + "・熱 " + w.path("heatMaxConsumption").asInt() + "/" + w.path("heatMinConsumption").asInt() + "）</span>");
            }
            row(h, "使っている武装", ws.isEmpty() ? "<span class='s'>なし</span>" : String.join("<br>", ws));
            JsonNode pairs = mek.tree.path("mekPairBlock").path("unkPair");
            List<String> bm = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) if (pairs.get(i).path("int1").asInt() == -1 && pairs.get(i).path("int2").asInt() == si) bm.add("基本動作表 [" + i + "]");
            if (!bm.isEmpty()) row(h, "基本動作", esc(String.join("、", bm)));
        }
        String hits = EventText.hitsSummary(p, waz.tree, si);
        row(h, "攻撃", hits.isEmpty() || hits.equals("-") ? "<span class='s'>なし</span>" : esc(hits));
        // outgoing references
        LinkedHashMap<String, int[]> spawn = new LinkedHashMap<>(), hitFx = new LinkedHashMap<>(), jumps = new LinkedHashMap<>();
        for (WazNav.Ev e : WazNav.events(waz.tree, si, -1)) collect(e.node, e.typeId() == 16 ? hitFx : spawn, jumps);
        row(h, "出す弾・エフェクト", refs(spawn));
        if (!hitFx.isEmpty()) row(h, "ヒットエフェクト", refs(hitFx));
        if (!jumps.isEmpty()) row(h, "技ジャンプ先", refs(jumps));
        // incoming
        RefIndex ri = p.refIndex();
        if (ri == null) row(h, "呼ばれる元", "<span class='s'>集計中…</span>");
        else {
            LinkedHashMap<String, int[]> in = new LinkedHashMap<>();
            for (RefIndex.Ref r : ri.referrers(wazIdx, si)) in.putIfAbsent(r.srcWaz + ":" + r.srcSkill + ":" + r.kind, new int[]{r.srcWaz, r.srcSkill, r.kind.equals("技ジャンプ") ? 1 : 0});
            List<String> l = new ArrayList<>();
            int n = 0;
            for (int[] r : in.values()) {
                if (n++ >= 40) { l.add("<span class='s'>… 他 " + (in.size() - 40) + " 件</span>"); break; }
                l.add(link(skillLabel(r[0], r[1]), () -> links.openSkill(r[0], r[1])) + (r[2] == 1 ? "<span class='s'>（技ジャンプ）</span>" : ""));
            }
            row(h, "呼ばれる元", l.isEmpty() ? "<span class='s'>なし（弾・エフェクトとしても技ジャンプ先としても使われていません）</span>" : String.join("<br>", l));
        }
        h.append("</table>");
        h.append("<p class='s'>下のタイムラインでバーをクリックするとそのイベントを右で編集できます。弾・エフェクトのバーはダブルクリックで生成先の技へ移動します。</p>");
        setHtml(h.toString());
    }

    void row(StringBuilder h, String k, String v) { h.append("<tr><td class='k' width='120' nowrap>").append(esc(k)).append("</td><td>").append(v).append("</td></tr>"); }

    String refs(LinkedHashMap<String, int[]> m) {
        if (m.isEmpty()) return "<span class='s'>なし</span>";
        List<String> l = new ArrayList<>();
        for (int[] r : m.values()) {
            int w = r[0] == -1 ? wazIdx : r[0];
            l.add(link(skillLabel(w, r[1]), () -> links.openSkill(w, r[1])) + (r[2] > 1 ? "<span class='s'>　×" + r[2] + "</span>" : ""));
        }
        return String.join("<br>", l);
    }

    String skillLabel(int w, int s) {
        Project.Mech m = p.mechOfWaz(w);
        List<String> names = p.skillNames(w);
        String nm = names != null && s >= 0 && s < names.size() ? names.get(s) : "?";
        String where = w == wazIdx ? "この waz" : m != null ? m.mekName : p.wazLabel(w);
        return where + "　技 " + s + " " + nm;
    }

    /** WazaSelect (wazFileNo / wazSequenceNo) into target, Term 技ジャンプ (int1 16, typeList[0] 1) into jumps. */
    static void collect(JsonNode n, LinkedHashMap<String, int[]> target, LinkedHashMap<String, int[]> jumps) {
        if (n == null) return;
        if (n.isObject()) {
            if (n.has("wazFileNo") && n.has("wazSequenceNo")) add(target, n.get("wazFileNo").asInt(), n.get("wazSequenceNo").asInt());
            if (n.path("int1").asInt(-1) == 16 && n.path("typeList").size() > 0 && n.path("typeList").get(0).asInt() == 1 && n.path("paramList").size() >= 2)
                add(jumps, n.get("paramList").get(0).asInt(), n.get("paramList").get(1).asInt());
        }
        if (n.isContainerNode()) for (JsonNode c : n) if (c.isContainerNode()) collect(c, target, jumps);
    }

    static void add(LinkedHashMap<String, int[]> m, int w, int s) {
        if (s < 0) return;
        int[] r = m.computeIfAbsent(w + ":" + s, k -> new int[]{w, s, 0});
        r[2]++;
    }
}
