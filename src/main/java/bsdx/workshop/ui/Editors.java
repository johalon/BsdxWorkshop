package bsdx.workshop.ui;

import bsdx.workshop.data.*;
import bsdx.workshop.ui.Form.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.giga.nexas.dto.bsdx.spm.Spm;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Property pages (auto-generated from waz_fields.json plus specialised panels). */
public final class Editors {
    private Editors() {}

    static final Set<String> BOOK = Set.of("offset", "slotNum", "typeId", "unitSlotNum", "buffer", "description", "unitQuantity", "unitDescription",
            "ceventHitUnitQuantity", "ceventEffectUnitQuantity", "count", "result", "flag", "type");

    // =============================================================== mek basic info
    static final String[][] MEK_BASIC = {
            {"名称", "mekName", "機体名", "s"}, {"名称", "mekNameEnglish", "機体名ルビ(英字, UTF-8)", "u"},
            {"名称", "pilotNameKanji", "パイロット名", "s"}, {"名称", "pilotNameRoma", "パイロットルビ(ローマ字, UTF-8)", "u"},
            {"名称", "mekDescription", "機体解説", "s"},
            {"耐久・装甲", "baseHealth", "耐久力（基礎HP/10。図鑑のLv4のHP）", ""}, {"耐久・装甲", "permanentArmor", "常時装甲（/100）", ""},
            {"耐久・装甲", "healthRecovery", "強さLv（敵として撃破されたときの回復量/3）", ""}, {"耐久・装甲", "forceOnKill", "フォース数（撃破時）", ""},
            {"性能ランク（図鑑）", "fightingAbility", "性能：格闘", "rank"}, {"性能ランク（図鑑）", "shootingAbility", "性能：射撃", "rank"},
            {"性能ランク（図鑑）", "durability", "性能：耐久力", "rank"}, {"性能ランク（図鑑）", "mobility", "性能：機動", "rank"},
            {"速度", "walkingSpeed", "速度：歩行", ""}, {"速度", "normalDashSpeed", "速度：ダッシュ(ND)", ""},
            {"速度", "searchDashSpeed", "速度：サーチダッシュ(SD)", ""}, {"速度", "boostDashSpeed", "速度：ブーストダッシュ(BD)", ""},
            {"速度", "autoHoverHeight", "浮遊高度", ""},
            {"ブースター・FCゲージ", "boosterLevel", "ブースターが1になるLv", ""}, {"ブースター・FCゲージ", "boosterIncreaseLevel", "ブースター増分（何Lvごとに+1）", ""},
            {"ブースター・FCゲージ", "energyIncreaseLevel1", "FCゲージLv1（このLv以上で+1）", ""}, {"ブースター・FCゲージ", "energyIncreaseLevel2", "FCゲージLv2（このLv以上で+1）", ""},
            {"その他", "comboImpactFactor", "コンボ感想反映率（浮きやすさ）", ""}, {"その他", "physicsWeight", "重量", ""}, {"その他", "mekType", "フラグ（図鑑の分類）", ""},
    };

    public static JComponent mekBasic(Ctx ctx, FileDoc mek) {
        JPanel root = Form.vbox();
        root.add(Form.note("機体ステータス（MEK_BASIC_INFO） — " + mek.name + "（元: " + mek.sourceLabel + "）"));
        Map<String, Grid> groups = new LinkedHashMap<>();
        String bp = "/mekBasicInfo";
        LinkedHashMap<Integer, String> rank = new LinkedHashMap<>();
        rank.put(0, "E"); rank.put(1, "E"); rank.put(2, "D"); rank.put(3, "C"); rank.put(4, "B"); rank.put(5, "A");
        for (String[] f : MEK_BASIC) {
            Grid g = groups.computeIfAbsent(f[0], k -> new Grid());
            String ptr = Json.child(bp, f[1]);
            if (Json.at(mek.tree, ptr) == null) continue;
            JLabel l = Form.label(f[2], "CONFIRMED", f[1], f[1]);
            if (f[3].equals("s") || f[3].equals("u")) g.add(l, ctx.add(new StrField(ctx, mek, ptr, f[2], f[3].equals("s"), f[1].equals("mekDescription") ? 40 : 20)));
            else if (f[3].equals("rank")) { g.add(l, ctx.add(new IntCombo(ctx, mek, ptr, f[2], rank))); g.hint(Form.legendNote(rank, false)); }
            else g.add(l, ctx.add(new IntField(ctx, mek, ptr, f[2])));
        }
        groups.forEach((k, g) -> root.add(Form.titled(k, g)));
        Grid raw = new Grid();
        for (String k : new String[]{"wazFileSequence", "spmFileSequence"}) {
            JTextField t = new JTextField(mek.tree.at(bp + "/" + k).asText());
            t.setEditable(false);
            raw.add(Form.label(k.equals("wazFileSequence") ? "技グループ（WazaGroup 番号）" : "スプライトグループ（SpriteGroup 番号）", "CONFIRMED", "登録の付け替えはこのツールでは行いません", k), t);
        }
        root.add(Form.titled("登録（表示のみ）", raw));
        return root;
    }

    // =============================================================== weapon
    static final Map<String, Map<Integer, String>> WEAPON_ENUMS = Map.of(
            "weaponCategory", Map.of(0, "通常", 1, "FC", 2, "イニシャライザ"),
            "weaponType", Map.of(0, "格闘", 1, "射撃"),
            "weaponIdentifier", Map.of(0, "通常（装備画面に表示）", 1, "HC（ヒートチャージ表示）", 2, "非表示（装備画面に出さない）"));
    static final String[][] WEAPON_TAGS = {{"meleeSkillFlag", "タイプ：格闘技"}, {"coldWeaponSkillFlag", "タイプ：武器"}, {"missileSkillFlag", "タイプ：ミサイル"},
            {"bulletCategorySkillFlag", "タイプ：実弾"}, {"opticalWeaponSkillFlag", "タイプ：光学兵器"}, {"droneSkillFlag", "タイプ：ビット"},
            {"explosiveSkillFlag", "タイプ：爆発物"}, {"defensiveWeaponSkillFlag", "タイプ：防御兵器"}};

    public static JComponent weapon(Ctx ctx, FileDoc mek, String key, FileDoc waz, int wazIdx) {
        JPanel root = Form.vbox();
        String wp = "/mekWeaponInfoMap/" + key;
        JsonNode w = Json.at(mek.tree, wp);
        int si = w.path("wazSequence").asInt();
        root.add(Form.note("武装 #" + key + " 「" + w.path("weaponName").asText() + "」 → 技 " + si + " 「" + (waz == null ? "?" : WazNav.skillName(waz.tree, si)) + "」\n"
                + "攻撃: " + (waz == null ? "-" : EventText.hitsSummary(ctx.p, waz.tree, si))));
        JButton open = new JButton("この技を開く");
        open.addActionListener(e -> ctx.nav.openSkill(wazIdx, Json.i(Json.at(mek.tree, wp), "wazSequence", si)));
        root.add(open);
        Grid g = new Grid();
        for (Fields.Field f : ctx.p.fields.mekWeapon) {
            if (f.key.contains("..")) {
                for (String[] t : WEAPON_TAGS) if (w.has(t[0])) {
                    g.add(Form.label(t[1], f.confidence, ">0 でタグ表示", t[0]), ctx.add(new IntField(ctx, mek, wp + "/" + t[0], t[1])));
                    g.hint(Form.legendNote(new TreeMap<>(Map.of(0, "タグなし", 1, "タグ表示（1 以上）")), !"CONFIRMED".equals(f.confidence)));
                }
                continue;
            }
            if (WEAPON_ENUMS.containsKey(f.key) && w.has(f.key)) {
                g.add(Form.label(f.nameJa, f.confidence, Fields.tip(f.ux, f.uxConf, f.semantics), f.key), ctx.add(new IntCombo(ctx, mek, wp + "/" + f.key, "武装#" + key + " " + f.nameJa, new TreeMap<>(WEAPON_ENUMS.get(f.key)))));
                g.hint(Form.legendNote(new TreeMap<>(WEAPON_ENUMS.get(f.key)), !"CONFIRMED".equals(f.confidence)));
                continue;
            }
            if (f.unknown()) continue;
            if (f.key.equals("wazSequence") && waz != null) {
                LinkedHashMap<Integer, String> sk = new LinkedHashMap<>();
                JsonNode sl = waz.tree.path("skillList");
                for (int i = 0; i < sl.size(); i++) sk.put(i, WazNav.skillName(waz.tree, i));
                g.add(Form.label(f.nameJa + "（この機体の waz の技）", f.confidence, Fields.tip(f.ux, f.uxConf, f.semantics), f.key), ctx.add(new IntCombo(ctx, mek, wp + "/wazSequence", "武装#" + key + " 技番号", sk)));
                continue;
            }
            if (f.key.equals("weaponDescription")) {
                g.add(Form.label(f.nameJa, f.confidence, Fields.tip(f.ux, f.uxConf, f.semantics), f.key), ctx.add(new Form.StrArea(ctx, mek, wp + "/weaponDescription", "武装#" + key + " 解説", true, 3)));
                continue;
            }
            Form.fieldRow(ctx, g, mek, wp, f, "武装#" + key + " ");
        }
        root.add(Form.titled("武装データ（mek）", g));
        Grid raw = new Grid();
        for (Fields.Field f : ctx.p.fields.mekWeapon) if (f.unknown()) Form.fieldRow(ctx, raw, mek, wp, f, "武装#" + key + " ");
        root.add(Form.titled("詳細(raw)", raw));
        return root;
    }

    // =============================================================== skill / turn
    public static JComponent skill(Ctx ctx, FileDoc waz, int wazIdx, int si, Project.Mech mech) { return skill(ctx, waz, wazIdx, si, mech, -1); }

    /** Preview panel + controls (direction default 2, hit rects default ON, play; a frame slider in sequence mode). */
    static JPanel previewBox(SpmPreview pv, SkillSeq seq) {
        JPanel ctl = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        JComboBox<String> dir = new JComboBox<>(new String[]{"方向0", "方向1", "方向2", "方向3", "方向4"});
        dir.setSelectedIndex(SpmPreview.DEFAULT_DIR);
        pv.setDir(SpmPreview.DEFAULT_DIR);
        dir.addActionListener(e -> pv.setDir(dir.getSelectedIndex()));
        JCheckBox hit = new JCheckBox("当たり判定", SpmPreview.DEFAULT_HIT);
        pv.setShowHit(SpmPreview.DEFAULT_HIT);
        hit.addActionListener(e -> pv.setShowHit(hit.isSelected()));
        JCheckBox play = new JCheckBox("再生", true);
        ctl.add(dir); ctl.add(hit); ctl.add(play);
        JPanel pvBox = new JPanel(new BorderLayout());
        pvBox.add(pv, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(ctl, BorderLayout.NORTH);
        if (seq != null && seq.total > 1) {
            JSlider sl = new JSlider(0, seq.total - 1, 0);
            sl.setToolTipText("フレーム（全体）。ドラッグすると一時停止してその位置を表示");
            boolean[] fromPv = {false};
            sl.addChangeListener(e -> { if (!fromPv[0]) { if (sl.getValueIsAdjusting() && play.isSelected()) { play.setSelected(false); pv.setPlaying(false); } pv.setGlobalFrame(sl.getValue()); } });
            javax.swing.Timer sync = new javax.swing.Timer(100, e -> { if (sl.getValue() != pv.globalFrame()) { fromPv[0] = true; sl.setValue(pv.globalFrame()); fromPv[0] = false; } });
            pv.addHierarchyListener(e -> { if (pv.isShowing()) sync.start(); else sync.stop(); });
            south.add(sl, BorderLayout.SOUTH);
        }
        play.addActionListener(e -> pv.setPlaying(play.isSelected()));
        pvBox.add(south, BorderLayout.SOUTH);
        return pvBox;
    }

    /** Skill page (turn &lt; 0) or turn page; both carry a sprite preview of the concatenated turns / of the turn. */
    public static JComponent skill(Ctx ctx, FileDoc waz, int wazIdx, int si, Project.Mech mech, int turn) { return skill(ctx, waz, wazIdx, si, mech, turn, true); }

    /** v5: sprite preview of a skill (turn &lt; 0: all turns) or one turn, or null when the skill has no turns. */
    public static JComponent skillPreview(Ctx ctx, FileDoc waz, int si, Project.Mech mech, int turn, int w, int h) {
        JsonNode s = Json.at(waz.tree, WazNav.skillPtr(si));
        if (s == null || s.path("phasesInfo").size() == 0) return null;
        boolean all = turn < 0 || turn >= s.path("phasesInfo").size();
        SkillSeq seq = all ? SkillSeq.all(waz.tree, si) : SkillSeq.turn(waz.tree, si, turn);
        SpmPreview pv = new SpmPreview(ctx.p);
        pv.setPreferredSize(new Dimension(w, h));
        pv.showSequence(seq, mech != null ? mech.spmIdx : -1, g -> ctx.nav.playhead(waz, si, seq, g));
        return previewBox(pv, seq);
    }

    /** v5: preview=false leaves the sprite preview out (the main window shows it in the overview). */
    public static JComponent skill(Ctx ctx, FileDoc waz, int wazIdx, int si, Project.Mech mech, int turn, boolean preview) {
        JPanel root = Form.vbox();
        String sp = WazNav.skillPtr(si);
        JsonNode s = Json.at(waz.tree, sp);
        StringBuilder sb = new StringBuilder();
        sb.append("技 ").append(si).append(" — ").append(waz.name).append("（元: ").append(waz.sourceLabel).append("）\n");
        JsonNode ph = s == null ? null : s.path("phasesInfo");
        if (ph != null) {
            sb.append("ターン数 ").append(ph.size()).append("  フレーム/ターン: ");
            for (int t = 0; t < ph.size(); t++) sb.append(t > 0 ? "/" : "").append(WazNav.turnLength(waz.tree, si, t));
            sb.append("\n攻撃: ").append(EventText.hitsSummary(ctx.p, waz.tree, si));
        }
        if (mech != null) {
            List<String> ws = new ArrayList<>();
            FileDoc mek = ctx.p.docIfLoaded(mech.mekFile);
            if (mek != null) mek.tree.path("mekWeaponInfoMap").fields().forEachRemaining(e -> {
                if (e.getValue().path("wazSequence").asInt() == si) ws.add("#" + e.getKey() + " " + e.getValue().path("weaponName").asText());
            });
            if (!ws.isEmpty()) sb.append("\nこの技を使う武装: ").append(String.join(", ", ws));
        }
        root.add(Form.note(sb.toString()));
        root.add(referrers(ctx, wazIdx, si, wazIdx));
        if (s != null && s.has("skillNameJapanese")) {
            Grid g = new Grid();
            g.add(Form.label("技名（日本語）", "CONFIRMED", "", "skillNameJapanese"), ctx.add(new StrField(ctx, waz, sp + "/skillNameJapanese", "技名", true, 24)));
            g.add(Form.label("技名（英字）", "CONFIRMED", "", "skillNameEnglish"), ctx.add(new StrField(ctx, waz, sp + "/skillNameEnglish", "技名(英)", true, 24)));
            root.add(Form.titled("技", g));
        }
        if (preview && s != null && s.path("phasesInfo").size() > 0) {
            boolean all = turn < 0 || turn >= s.path("phasesInfo").size();
            SkillSeq seq = all ? SkillSeq.all(waz.tree, si) : SkillSeq.turn(waz.tree, si, turn);
            SpmPreview pv = new SpmPreview(ctx.p);
            pv.setPreferredSize(new Dimension(260, 280));
            pv.showSequence(seq, mech != null ? mech.spmIdx : -1, g -> ctx.nav.playhead(waz, si, seq, g));
            String title = all ? "スプライト プレビュー：全ターン連結（ターン0→" + (seq.turns.length - 1) + "、ジャンプ無視）" : "スプライト プレビュー：ターン " + turn;
            root.add(Form.titled(title, previewBox(pv, seq)));
            root.add(Form.note("[0] スプライト のイベントを順に表示します（イベント区間中は指定パターンを固定表示、区間の間は直前を保持＝推定）。"
                    + (mech == null ? "\n共通 waz の「-1 = 自機」は機体が決まらないため表示できません。" : "")));
        }
        if (preview) root.add(Form.note(turn < 0 ? "下のタイムラインは全ターンを連結表示します。バー / ターン見出し / スロット名を 1 クリックでその要素へ移動します。"
                : "下のタイムラインはこのターンだけを表示します（技を選ぶと全ターン連結）。"));
        return root;
    }

    /** "who uses this skill" note with a warning when other mechs reference it. */
    static JComponent referrers(Ctx ctx, int wazIdx, int si, int ownWaz) {
        RefIndex r = ctx.p.refIndex();
        if (r == null) return Form.note("参照元: 集計中…");
        List<RefIndex.Ref> refs = r.referrers(wazIdx, si);
        if (refs.isEmpty()) return Form.note("参照元: なし（他の技から生成・ジャンプされていません）");
        Map<String, Set<Integer>> by = new TreeMap<>();
        Set<Integer> mechs = new TreeSet<>();
        for (RefIndex.Ref ref : refs) {
            Project.Mech m = ctx.p.mechOfWaz(ref.srcWaz);
            String who = m != null ? m.mekName + "(" + m.name + ")" : ctx.p.wazLabel(ref.srcWaz);
            if (m != null) mechs.add(ref.srcWaz);
            by.computeIfAbsent(who, k -> new TreeSet<>()).add(ref.srcSkill);
        }
        StringBuilder sb = new StringBuilder("参照元: ");
        int n = 0;
        for (Map.Entry<String, Set<Integer>> e : by.entrySet()) {
            if (n++ > 0) sb.append(", ");
            if (n > 12) { sb.append("… 他 ").append(by.size() - 12).append(" 件"); break; }
            sb.append(e.getKey()).append(" 技").append(e.getValue().size() > 4 ? e.getValue().size() + "個" : e.getValue().toString());
        }
        mechs.remove(ownWaz);
        JTextArea a = Form.note(sb.toString());
        if (mechs.size() >= 1 && (ctx.p.mechOfWaz(wazIdx) == null)) {
            a.setText("⚠ この共通技は " + mechs.size() + " 機の waz から使われています。変更はそれらすべてに影響します。\n" + sb);
            a.setForeground(new Color(170, 30, 0));
        }
        return a;
    }

    // =============================================================== events
    public static JComponent event(Ctx ctx, FileDoc waz, int wazIdx, Project.Mech mech, String ptr) {
        JsonNode o = Json.at(waz.tree, ptr);
        int ty = o.path("typeId").asInt(-1);
        Fields.ClassSpec spec = ctx.p.fields.classOf(ty);
        int slot = slotOfPtr(waz.tree, ptr);
        Fields.Slot sl = ctx.p.fields.slots.get(slot);
        int selfSpm = mech != null ? mech.spmIdx : -1;
        JPanel edit = Form.vbox();
        edit.add(Form.note("[" + slot + "] " + (sl == null ? "" : sl.nameJa) + " — " + (spec == null ? "type " + ty : spec.name)
                + (sl == null ? "" : "（" + confJa(sl.confidence) + "）" + (sl.ux.isEmpty() ? "" : "\n体感: " + sl.ux + "（" + sl.uxConf + "）") + "\n" + sl.semantics) + (spec == null ? "" : "\n" + spec.summary)));
        Set<String> shown = new HashSet<>();
        Grid frames = new Grid();
        for (String k : new String[]{"startFrame", "endFrame"}) {
            frames.add(Form.label(k.equals("startFrame") ? "開始フレーム" : "終了フレーム", "CONFIRMED", "ターン内のフレーム（0 始まり）", k), ctx.add(new IntField(ctx, waz, ptr + "/" + k, k)));
            shown.add(k);
        }
        edit.add(Form.titled("タイムライン", frames));
        String cls = spec == null ? "" : spec.name;
        switch (cls) {
            case "CEventHit": edit.add(hitPanel(ctx, waz, wazIdx, ptr, spec, shown)); break;
            case "CEventSprite": edit.add(spritePanel(ctx, waz, ptr, selfSpm, shown)); break;
            case "CEventSe": edit.add(Form.titled("ＳＥ（候補から確率で選ぶ）", seRows(ctx, waz, ptr))); shown.add("count"); break;
            case "CEventVoice": edit.add(Form.titled("ボイス候補", voiceRows(ctx, waz, ptr))); break;
            case "CEventEffect": edit.add(effectPanel(ctx, waz, wazIdx, ptr)); break;
            case "CEventWazaSelect": edit.add(Form.titled("参照する技", wazaSelect(ctx, waz, ptr, wazIdx))); shown.add("wazFileNo"); shown.add("wazSequenceNo"); break;
            case "CEventChange": edit.add(changePanel(ctx, waz, ptr)); break;
            default:
                if (o.has("bsdxInfoCollectionList")) edit.add(Form.titled("条件式 / 指定子（Term）", termEditor(ctx, waz, ptr + "/bsdxInfoCollectionList/0")));
        }
        if (spec != null) {
            Grid g = new Grid();
            for (Fields.Field f : spec.fields) {
                if (shown.contains(f.key) || f.unknown()) continue;
                Form.fieldRow(ctx, g, waz, ptr, f, "[" + slot + "] ");
                shown.add(f.key);
            }
            if (g.getComponentCount() > 0) edit.add(Form.titled("値", g));
            if (sl != null && !sl.mainValue.isEmpty()) edit.add(Form.note("主値は " + sl.mainValue + "（値イベントは開始→終了で補間されます）"));
        }
        if (!cls.equals("CEventHit") && !cls.equals("CEventEffect")) {
            JComponent subs = subsSection(ctx, waz, wazIdx, ptr, spec, Set.of(), 0);
            if (subs != null) edit.add(Form.titled("サブ項目", subs));
        }
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("編集", scroll(edit));
        tabs.addTab("詳細(raw)", scroll(rawPanel(ctx, waz, ptr, spec, shown)));
        return tabs;
    }

    static String confJa(String c) { return "CONFIRMED".equals(c) ? "確認済" : "LIKELY".equals(c) ? "推定" : "不明"; }

    static int slotOfPtr(JsonNode tree, String ptr) {
        int i = ptr.indexOf("/skillInfoObjectList/");
        if (i < 0) return -1;
        return tree.at(ptr.substring(0, i) + "/unitQuantity").asInt(-1);
    }

    /** Panel that follows the viewport width (so wide grids / wrapped notes never run off to the right). */
    static final class WidthPanel extends JPanel implements Scrollable {
        WidthPanel() { super(new BorderLayout()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 200; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    public static JScrollPane scroll(JComponent c) {
        WidthPanel wrap = new WidthPanel();
        wrap.add(c, BorderLayout.NORTH);
        JScrollPane s = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.setBorder(BorderFactory.createEmptyBorder());
        SwingUtilities.invokeLater(() -> s.getViewport().setViewPosition(new Point(0, 0)));
        return s;
    }

    /** UNKNOWN fields + all other scalar values + the JSON of the event. */
    static JComponent rawPanel(Ctx ctx, FileDoc doc, String ptr, Fields.ClassSpec spec, Set<String> shown) {
        JPanel root = Form.vbox();
        root.add(Form.note("意味が確認されていない値です。変更は自己責任で（実機で確かめてください）。"));
        Grid g = new Grid();
        JsonNode o = Json.at(doc.tree, ptr);
        if (spec != null) for (Fields.Field f : spec.fields) if (f.unknown() && !shown.contains(f.key)) { Form.fieldRow(ctx, g, doc, ptr, f, ""); shown.add(f.key); }
        o.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber() && !shown.contains(e.getKey()) && !BOOK.contains(e.getKey()))
                g.add(Form.label(e.getKey(), "UNKNOWN", "辞書に無いキー"), ctx.add(new IntField(ctx, doc, Json.child(ptr, e.getKey()), e.getKey())));
        });
        root.add(Form.titled("値（raw）", g));
        JTextArea js = new JTextArea();
        js.setEditable(false);
        js.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        ctx.add(() -> {
            try {
                String t = ctx.p.codec.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Json.at(doc.tree, ptr));
                js.setText(t.length() > 60000 ? t.substring(0, 60000) + "\n…" : t);
                js.setCaretPosition(0);
            } catch (Exception ex) { js.setText(ex.toString()); }
        });
        root.add(Form.titled("JSON（表示のみ）  " + ptr, js));
        return root;
    }

    /** Present sub-objects of an object (Effect/Hit/Nokezori/Charge ...), rendered generically. */
    static JComponent subsSection(Ctx ctx, FileDoc doc, int wazIdx, String objPtr, Fields.ClassSpec spec, Set<Integer> skip, int depth) {
        JsonNode o = Json.at(doc.tree, objPtr);
        String key = Json.subListKey(o);
        if (key == null || depth > 3) return null;
        JPanel box = Form.vbox();
        JsonNode list = o.get(key);
        for (int k = 0; k < list.size(); k++) {
            JsonNode u = list.get(k);
            int idx = u.path("unitSlotNum").asInt();
            if (skip.contains(idx) || u.path("buffer").asInt() == 0 || !u.has("data") || u.get("data").isNull()) continue;
            String dp = Json.child(objPtr, key) + "/" + k + "/data";
            Fields.Sub sub = spec == null ? null : spec.subs.get(idx);
            String title = "[" + idx + "] " + (sub != null ? sub.nameJa + (sub.confidence.equals("LIKELY") ? "（推定）" : sub.confidence.equals("UNKNOWN") ? "（不明）" : "") : u.path("description").asText(""));
            JPanel tp = Form.titled(title, subObject(ctx, doc, wazIdx, dp, depth + 1));
            if (sub != null) { String t = Fields.tip(sub.ux, sub.uxConf, sub.semantics); if (!t.isEmpty()) tp.setToolTipText(Form.html(t)); }
            box.add(tp);
        }
        return box.getComponentCount() == 0 ? null : box;
    }

    static JComponent subObject(Ctx ctx, FileDoc doc, int wazIdx, String dp, int depth) {
        JsonNode d = Json.at(doc.tree, dp);
        int ty = d.path("typeId").asInt(-1);
        Fields.ClassSpec cs = ctx.p.fields.classOf(ty);
        if (ty == 23) return seRows(ctx, doc, dp);
        if (ty == 4) return wazaSelect(ctx, doc, dp, wazIdx);
        JPanel box = Form.vbox();
        if (d.has("bsdxInfoCollectionList")) box.add(termEditor(ctx, doc, dp + "/bsdxInfoCollectionList/0"));
        Grid g = new Grid();
        if (cs != null) for (Fields.Field f : cs.fields) {
            if (f.key.equals("startFrame") || f.key.equals("endFrame")) {
                if (!(ty == 0 || ty == 1 || ty == 2 || ty == 3)) continue;
            }
            Form.fieldRow(ctx, g, doc, dp, f, "");
        }
        if (g.getComponentCount() > 0) box.add(g);
        JComponent nested = subsSection(ctx, doc, wazIdx, dp, cs, Set.of(), depth);
        if (nested != null) box.add(nested);
        return box;
    }

    /** Term: readable text + editable $v parameters. */
    static JComponent termEditor(Ctx ctx, FileDoc doc, String tp) {
        JPanel box = Form.vbox();
        JLabel txt = new JLabel();
        ctx.add(() -> txt.setText("<html>" + Form.esc(EventText.termText(ctx.p, Json.at(doc.tree, tp))) + "</html>"));
        box.add(txt);
        JsonNode pl = doc.tree.at(tp + "/paramList");
        if (pl.isArray() && pl.size() > 0) {
            Grid g = new Grid();
            for (int i = 0; i < pl.size(); i++) g.add(Form.label("値 $v" + (i + 1), "CONFIRMED", "paramList[" + i + "]", "paramList[" + i + "]"), ctx.add(new IntField(ctx, doc, tp + "/paramList/" + i, "Term 値" + (i + 1))));
            box.add(g);
        }
        return box;
    }

    // ---------------------------------------------------------------- Hit
    static final String[] NOKE_TABS = {"地上", "空中", "空中→地上", "ダウン"};

    static JComponent hitPanel(Ctx ctx, FileDoc waz, int wazIdx, String ptr, Fields.ClassSpec spec, Set<String> shown) {
        JPanel root = Form.vbox();
        Grid atk = new Grid();
        String dmg = WazNav.damagePtr(waz.tree, ptr);
        if (dmg != null) {
            String tptr = dmg.replace("/paramList/0", "/typeList/0");
            Fields.Sub s6 = spec == null ? null : spec.subs.get(6);
            atk.add(Form.label("威力", "CONFIRMED", Fields.tip(s6 == null ? "" : s6.ux, s6 == null ? "" : s6.uxConf, "攻撃力 Term の値（exe のダメージ計算 0x63ba40 で確認）"), "サブ[6] paramList[0]"), ctx.add(new IntField(ctx, waz, dmg, "威力")));
            if (Json.at(waz.tree, tptr) != null) {
                LinkedHashMap<Integer, String> t = new LinkedHashMap<>();
                t.put(0, "直値"); t.put(1, "自分のHPの%"); t.put(2, "敵のHPの%");
                atk.add(Form.label("威力の種類", "CONFIRMED", "typeList[0]", "サブ[6] typeList[0]"), ctx.add(new IntCombo(ctx, waz, tptr, "威力の種類", t)));
                atk.hint(Form.legendNote(t, false));
            }
        } else atk.add(Form.label("威力", "CONFIRMED", ""), new JLabel("（攻撃力 Term なし）"));
        String[] main = {"hitCount", "hitInterval", "minDamage", "chargeDamageRate", "int15", "int16", "int13", "int11",
                "startComboCorrection", "internalCorrection", "midComboCorrection", "endCorrection", "attackTargetType"};
        for (String k : main) {
            Fields.Field f = spec.field(k);
            if (f == null) continue;
            Form.fieldRow(ctx, atk, waz, ptr, f, "Hit ");
            shown.add(k);
        }
        atk.full(Form.note("ヒット数 -1 / 9999 = 判定中は無制限。ヒットストップ・ヒット数などの「推定」は実機で確認してください。"));
        root.add(Form.titled("攻撃", atk));
        // reactions
        JTabbedPane nt = new JTabbedPane();
        Fields.ClassSpec ns = ctx.p.fields.classes.get("CEventNokezori");
        LinkedHashMap<Integer, String> types = new LinkedHashMap<>();
        types.put(0, "指定（この欄の値を使う）"); types.put(1, "地上→地上と同じ"); types.put(2, "空中と同じ"); types.put(3, "空中→地上と同じ"); types.put(4, "ダウンと同じ"); types.put(5, "なし");
        for (int i = 0; i < 4; i++) {
            String np = WazNav.subPtr(waz.tree, ptr, 17 + i);
            if (np == null) { nt.addTab(NOKE_TABS[i], Form.note("（この欄はありません）")); continue; }
            Grid g = new Grid();
            Fields.Field nf1 = ns == null ? null : ns.field("int1");
            g.add(Form.label("処理タイプ", "CONFIRMED", Fields.tip(nf1 == null ? "" : nf1.ux, nf1 == null ? "" : nf1.uxConf, "1-4 は他の欄と同じ動作、5 はのけぞりなし"), "int1"), ctx.add(new IntCombo(ctx, waz, np + "/int1", "のけぞり(" + NOKE_TABS[i] + ") 処理タイプ", types)));
            g.hint(Form.legendNote(types, false));
            List<JComponent> dep = new ArrayList<>();
            for (Fields.Field f : ns.fields) {
                if (f.key.equals("int1") || f.key.equals("startFrame") || f.key.equals("endFrame") || f.unknown()) continue;
                int before = g.getComponentCount();
                Form.fieldRow(ctx, g, waz, np, f, "のけぞり(" + NOKE_TABS[i] + ") ");
                for (int c = before; c < g.getComponentCount(); c++) dep.add((JComponent) g.getComponent(c));
            }
            JComponent subs = subsSection(ctx, waz, wazIdx, np, ns, Set.of(), 1);
            if (subs != null) g.full(subs);
            final String fnp = np;
            ctx.add(() -> { boolean on = waz.tree.at(fnp + "/int1").asInt() == 0; for (JComponent c : dep) c.setEnabled(on); });
            nt.addTab(NOKE_TABS[i], g);
        }
        root.add(Form.titled("のけぞり・吹き飛び・ダウン（被弾側の状態ごと）", nt));
        String he = WazNav.subPtr(waz.tree, ptr, 16);
        if (he != null) root.add(Form.titled("ヒットエフェクト", wazaSelect(ctx, waz, he, wazIdx)));
        String hs = WazNav.subPtr(waz.tree, ptr, 28);
        if (hs != null) root.add(Form.titled("ヒットＳＥ", seRows(ctx, waz, hs)));
        JComponent other = subsSection(ctx, waz, wazIdx, ptr, spec, Set.of(6, 16, 17, 18, 19, 20, 28), 0);
        if (other != null) root.add(Form.titled("その他のサブ項目", other));
        return root;
    }

    // ---------------------------------------------------------------- SE / Voice
    static JComponent seRows(Ctx ctx, FileDoc doc, String ptr) {
        JPanel box = Form.vbox();
        JsonNode l = doc.tree.at(ptr + "/byteDataList");
        for (int i = 0; i < l.size(); i++) box.add(ctx.add(new CandRow(ctx, doc, ptr + "/byteDataList/" + i, true)));
        if (l.size() == 0) box.add(new JLabel("（候補なし）"));
        return box;
    }

    static JComponent voiceRows(Ctx ctx, FileDoc doc, String ptr) {
        JPanel box = Form.vbox();
        JsonNode l = doc.tree.at(ptr + "/byteDataList");
        for (int i = 0; i < l.size(); i++) box.add(ctx.add(new CandRow(ctx, doc, ptr + "/byteDataList/" + i, false)));
        if (l.size() == 0) box.add(new JLabel("（候補なし）"));
        return box;
    }

    /** One SE {cat,item,prob,flag} or voice {char,line,prob} candidate. */
    static final class CandRow extends JPanel implements Reloadable {
        final Ctx ctx; final FileDoc doc; final String ptr; final boolean se; final int n;
        final JComboBox<Item> a = new JComboBox<>(), b = new JComboBox<>();
        final JTextField prob = new JTextField(4), flag = new JTextField(3);
        boolean loading;
        CandRow(Ctx ctx, FileDoc doc, String ptr, boolean se) {
            super(new FlowLayout(FlowLayout.LEFT, 3, 1));
            this.ctx = ctx; this.doc = doc; this.ptr = ptr; this.se = se; this.n = se ? 4 : 3;
            setAlignmentX(LEFT_ALIGNMENT);
            JsonNode list = se ? ctx.p.seList() : ctx.p.voiceList();
            for (int i = 0; i < list.size(); i++) {
                JsonNode c = list.get(i);
                a.addItem(new Item(i, i + ": " + (se ? Json.s(c, "seType", "") + " [" + Json.s(c, "seTypeCodeName", "") + "]" : Json.s(c, "characterName", "") + " [" + Json.s(c, "characterCodeName", "") + "]")));
            }
            a.setPrototypeDisplayValue(new Item(0, "00: XXXXXXXXXXXXXXXXXX"));
            b.setPrototypeDisplayValue(new Item(0, "000: XXXXXXXXXXXXXXXXXXXXXX"));
            add(new JLabel(se ? "カテゴリ" : "キャラ")); add(a);
            add(new JLabel(se ? "項目" : "台詞")); add(b);
            add(new JLabel("確率%")); add(prob);
            if (se) { add(new JLabel("フラグ")); add(flag); }
            JButton play = new JButton("▶");
            play.setToolTipText("試聴（" + (se ? "Se.pac" : "Voice.pac") + " かオーバーレイにファイルがあるとき）。F9 でも再生");
            play.setMargin(new Insets(0, 4, 0, 4));
            play.addActionListener(e -> playNow());
            add(play);
            a.addActionListener(e -> { if (!loading) { fillB(((Item) a.getSelectedItem()).value); Item first = firstExisting(); commit(first == null ? 0 : first.value); } });
            b.addActionListener(e -> { if (!loading && b.getSelectedItem() != null) commit(((Item) b.getSelectedItem()).value); });
            prob.addActionListener(e -> commit(-1));
            flag.addActionListener(e -> commit(-1));
            java.awt.event.FocusAdapter fa = new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { commit(-1); } };
            prob.addFocusListener(fa); flag.addFocusListener(fa);
        }
        /** Plays the selected candidate (background decode). */
        void playNow() {
            int[] v = Json.bytesToInts(Json.at(doc.tree, ptr), n);
            playSound(ctx, se ? ctx.p.seBaseName(v[0], v[1]) : ctx.p.voiceBaseName(v[0], v[1]), se ? ctx.p.seLabel(v[0], v[1]) : ctx.p.voiceLabel(v[0], v[1]));
        }
        Item firstExisting() {
            for (int i = 0; i < b.getItemCount(); i++) if (!b.getItemAt(i).label.contains("(空き)")) return b.getItemAt(i);
            return null;
        }
        void fillB(int cat) {
            loading = true;
            b.removeAllItems();
            if (se) {
                JsonNode items = cat >= 0 && cat < ctx.p.seList().size() ? ctx.p.seList().get(cat).path("seItems") : null;
                if (items != null) for (int i = 0; i < items.size(); i++) {
                    JsonNode it = items.get(i);
                    b.addItem(new Item(i, i + ": " + (Json.i(it, "existFlag", 0) == 0 ? "(空き)" : Json.s(it, "seItemName", "") + "  <" + Json.s(it, "seFileName", "") + ">")));
                }
            } else {
                JsonNode vs = cat >= 0 && cat < ctx.p.voiceList().size() ? ctx.p.voiceList().get(cat).path("voices") : null;
                if (vs != null) for (int i = 0; i < vs.size(); i++) {
                    JsonNode it = vs.get(i);
                    b.addItem(new Item(i, i + ": " + (Json.i(it, "existFlag", 0) == 0 ? "(空き)" : "「" + Json.s(it, "voice", "") + "」 <" + Json.s(it, "voiceFileName", "") + ">")));
                }
            }
            loading = false;
        }
        void commit(int newItem) {
            int[] v = Json.bytesToInts(Json.at(doc.tree, ptr), n);
            int[] nv = v.clone();
            Item ai = (Item) a.getSelectedItem();
            if (ai != null) nv[0] = ai.value;
            if (newItem >= 0) nv[1] = newItem;
            try {
                nv[2] = Integer.parseInt(prob.getText().trim());
                if (se) nv[3] = Integer.parseInt(flag.getText().trim());
            } catch (NumberFormatException ex) { Toolkit.getDefaultToolkit().beep(); reload(); return; }
            if (Arrays.equals(v, nv)) return;
            String lab = se ? "SE = " + ctx.p.seLabel(nv[0], nv[1]) : "ボイス = " + ctx.p.voiceLabel(nv[0], nv[1]);
            ctx.p.undo.set(doc, ptr, Json.intsToBytes(nv), lab + "  (" + doc.name + ")");
        }
        @Override public void reload() {
            loading = true;
            int[] v = Json.bytesToInts(Json.at(doc.tree, ptr), n);
            selectValue(a, v[0]);
            fillB(v[0]);
            loading = true;
            selectValue(b, v[1]);
            prob.setText("" + v[2]);
            if (se) flag.setText("" + v[3]);
            Color bg = doc.isChanged(ptr) ? Form.CHANGED : null;
            setBackground(bg);
            setOpaque(bg != null);
            loading = false;
        }
    }

    /** Resolves and plays an SE/voice base name through the VFS; reports problems on the status line. */
    public static void playSound(Ctx ctx, String base, String label) {
        if (base == null) { ctx.nav.status("再生できません: 空き項目です（" + label + "）"); return; }
        String f = ctx.p.soundFile(base);
        if (f == null) { ctx.nav.status("再生できません: " + base + ".ogg/.wav がどの pac にもありません（ゲームフォルダの Se.pac / Voice.pac が必要）"); return; }
        ctx.nav.status("再生中: " + label + "  <" + f + ">");
        new Thread(() -> {
            try {
                bsdx.workshop.audio.SoundPlayer.Decoded d = bsdx.workshop.audio.SoundPlayer.play(ctx.p.fs.read(f));
                SwingUtilities.invokeLater(() -> ctx.nav.status(String.format("再生中: %s  <%s> %s %.0fHz %dch %.2f秒", label, f, d.kind, d.format.getSampleRate(), d.format.getChannels(), d.seconds())));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> ctx.nav.status("再生できません: " + f + ": " + ex.getMessage()));
            }
        }, "se-play").start();
    }

    static void selectValue(JComboBox<Item> c, int v) {
        for (int i = 0; i < c.getItemCount(); i++) if (c.getItemAt(i).value == v) { c.setSelectedIndex(i); return; }
        Item it = new Item(v, v + " (範囲外!)");
        c.addItem(it);
        c.setSelectedItem(it);
    }

    // ---------------------------------------------------------------- WazaSelect (bullet / effect link)
    static JComponent wazaSelect(Ctx ctx, FileDoc doc, String ptr, int ownWaz) {
        JPanel box = Form.vbox();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JComboBox<Item> file = new JComboBox<>(), sk = new JComboBox<>();
        file.addItem(new Item(-1, "-1: 自分の waz"));
        for (int i = 0; i < ctx.p.wazaList().size(); i++) file.addItem(new Item(i, ctx.p.wazLabel(i)));
        file.setPrototypeDisplayValue(new Item(0, "000: XXXXXXXXXXXXXXXXXX"));
        sk.setPrototypeDisplayValue(new Item(0, "000: XXXXXXXXXXXXXXXXXXXXXXXX"));
        JButton open = new JButton("→ 開く");
        row.add(new JLabel("waz")); row.add(file); row.add(new JLabel("技")); row.add(sk); row.add(open);
        box.add(row);
        JPanel refBox = Form.vbox();
        box.add(refBox);
        boolean[] loading = {false};
        Runnable fillSk = () -> {
            sk.removeAllItems();
            Item f = (Item) file.getSelectedItem();
            int fi = f == null ? -1 : (f.value == -1 ? ownWaz : f.value);
            List<String> names = ctx.p.skillNames(fi);
            if (names != null) for (int i = 0; i < names.size(); i++) sk.addItem(new Item(i, i + ": " + names.get(i)));
        };
        Reloadable r = () -> {
            loading[0] = true;
            JsonNode o = Json.at(doc.tree, ptr);
            int f = o.path("wazFileNo").asInt(), q = o.path("wazSequenceNo").asInt();
            selectValue(file, f);
            fillSk.run();
            selectValue(sk, q);
            refBox.removeAll();
            refBox.add(referrers(ctx, f == -1 ? ownWaz : f, q, ownWaz));
            refBox.revalidate();
            Color bg = doc.isChanged(ptr + "/wazFileNo") || doc.isChanged(ptr + "/wazSequenceNo") ? Form.CHANGED : null;
            row.setBackground(bg); row.setOpaque(bg != null);
            loading[0] = false;
        };
        file.addActionListener(e -> {
            if (loading[0] || file.getSelectedItem() == null) return;
            int f = ((Item) file.getSelectedItem()).value;
            ctx.p.undo.begin("参照 waz = " + ctx.p.wazLabel(f));
            ctx.p.undo.set(doc, ptr + "/wazFileNo", IntNode.valueOf(f), "");
            int q = doc.tree.at(ptr + "/wazSequenceNo").asInt();
            if (q >= ctx.p.wazParam(f)) ctx.p.undo.set(doc, ptr + "/wazSequenceNo", IntNode.valueOf(0), "");
            ctx.p.undo.end();
        });
        sk.addActionListener(e -> {
            if (loading[0] || sk.getSelectedItem() == null) return;
            ctx.p.undo.set(doc, ptr + "/wazSequenceNo", IntNode.valueOf(((Item) sk.getSelectedItem()).value), "参照する技 = " + sk.getSelectedItem() + "  (" + doc.name + ")");
        });
        open.addActionListener(e -> {
            JsonNode o = Json.at(doc.tree, ptr);
            int f = o.path("wazFileNo").asInt();
            ctx.nav.openSkill(f == -1 ? ownWaz : f, o.path("wazSequenceNo").asInt());
        });
        ctx.add(r);
        return box;
    }

    // ---------------------------------------------------------------- Effect (projectile spawn)
    static JComponent effectPanel(Ctx ctx, FileDoc waz, int wazIdx, String ptr) {
        JPanel root = Form.vbox();
        String ws = WazNav.subPtr(waz.tree, ptr, 3);
        if (ws != null) root.add(Form.titled("生成する技（弾・エフェクト）— 弾の威力は生成先の技の「攻撃」にあります", wazaSelect(ctx, waz, ws, wazIdx)));
        Grid g = new Grid();
        String[][] vals = {{"4", "同時発射数"}, {"5", "最大発射数"}, {"6", "発射間隔"}};
        for (String[] v : vals) {
            String sp = WazNav.subPtr(waz.tree, ptr, Integer.parseInt(v[0]));
            if (sp == null) continue;
            JsonNode d = Json.at(waz.tree, sp);
            String key = d.has("int5") ? "int5" : "int4";
            g.add(Form.label(v[1], "LIKELY", "サブ[" + v[0] + "] の主値 " + key, "サブ[" + v[0] + "] " + key), ctx.add(new IntField(ctx, waz, sp + "/" + key, v[1])));
        }
        if (g.getComponentCount() > 0) root.add(Form.titled("発射", g));
        JComponent other = subsSection(ctx, waz, wazIdx, ptr, ctx.p.fields.classes.get("CEventEffect"), Set.of(3, 4, 5, 6), 0);
        if (other != null) root.add(Form.titled("その他のサブ項目（位置・ベクトル・速度など）", other));
        return root;
    }

    // ---------------------------------------------------------------- Change (cancel / branch / loop)
    static JComponent changePanel(Ctx ctx, FileDoc waz, String ptr) {
        JPanel root = Form.vbox();
        JLabel txt = new JLabel();
        ctx.add(() -> txt.setText("<html><body style='width:380px'>" + Form.esc(EventText.summary(ctx.p, Json.at(waz.tree, ptr), -1)) + "</body></html>"));
        root.add(Form.titled("変更処理（条件 ⇒ 飛び先）", txt));
        JsonNode l1 = waz.tree.at(ptr + "/bsdxInfoCollectionList1");
        for (int i = 0; i < l1.size(); i++) root.add(Form.titled("条件 " + (i + 1), termEditor(ctx, waz, ptr + "/bsdxInfoCollectionList1/" + i)));
        if (waz.tree.at(ptr + "/bsdxInfoCollectionList2/0").isObject()) root.add(Form.titled("飛び先", termEditor(ctx, waz, ptr + "/bsdxInfoCollectionList2/0")));
        root.add(Form.note("条件・飛び先の種類（項目）の変更はこのツールでは行いません（値 $v のみ編集可）。"));
        return root;
    }

    // ---------------------------------------------------------------- Sprite
    static JComponent spritePanel(Ctx ctx, FileDoc waz, String ptr, int selfSpm, Set<String> shown) {
        JPanel root = Form.vbox();
        Grid g = new Grid();
        LinkedHashMap<Integer, String> files = new LinkedHashMap<>();
        files.put(-1, "自機" + (selfSpm >= 0 ? "（" + ctx.p.spmFile(selfSpm) + "）" : ""));
        for (int i = 0; i < ctx.p.spriteList().size(); i++) {
            String f = ctx.p.spmFile(i);
            if (f != null) files.put(i, f);
        }
        IntCombo fileC = new IntCombo(ctx, waz, ptr + "/spmFileSequence", "スプライトファイル", files);
        g.add(Form.label("スプライトファイル", "CONFIRMED", "-1 = 自機の spm", "spmFileSequence"), ctx.add(fileC));
        IntCombo animC = new IntCombo(ctx, waz, ptr + "/actionGroupNumber", "アニメ", new LinkedHashMap<>());
        g.add(Form.label("アニメ", "CONFIRMED", "spm のアニメ番号", "actionGroupNumber"), animC);
        g.add(Form.label("パターン", "CONFIRMED", "アニメ内のパターン番号", "actionNumber"), ctx.add(new IntField(ctx, waz, ptr + "/actionNumber", "パターン")));
        shown.addAll(List.of("spmFileSequence", "actionGroupNumber", "actionNumber"));
        root.add(Form.titled("モーション", g));
        SpmPreview pv = new SpmPreview(ctx.p);
        root.add(Form.titled("プレビュー（機体・戦闘スプライトのみ）", previewBox(pv, null)));
        String[] lastFile = {null};
        ctx.add(() -> {
            JsonNode o = Json.at(waz.tree, ptr);
            int sf = o.path("spmFileSequence").asInt();
            int spmIdx = sf == -1 ? selfSpm : sf;
            String fn = ctx.p.spmFile(spmIdx);
            if (!Objects.equals(fn, lastFile[0])) {
                lastFile[0] = fn;
                LinkedHashMap<Integer, String> an = new LinkedHashMap<>();
                Spm s = fn == null ? null : ctx.p.spm(fn);
                if (s != null) for (int i = 0; i < s.getAnimData().size(); i++) {
                    Spm.SPMAnimData a = s.getAnimData().get(i);
                    an.put(i, (a.getAnimName() == null ? "" : a.getAnimName().replace("\r", "").trim()) + "（" + (a.getNumPat() & 0xFFFF) + "パターン）");
                }
                animC.setItems(an);
            }
            animC.reload();
            pv.show(fn, o.path("actionGroupNumber").asInt(), o.path("actionNumber").asInt());
        });
        ctx.binds.add(animC);
        return root;
    }
}
