package bsdx.workshop.ui;

import bsdx.workshop.data.*;
import bsdx.workshop.ui.Form.*;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI table editor (mek mekAiInfoList): AI types -> CPU events (移動 = CCpuEventMove, 攻撃 = CCpuEventAttack).
 * Field names: NeXAS_DX DTO names / the exe's property list CCPU_EVENT_TYPES (MEK_AI).
 * -999 = 無制限. The attack weapon is a dropdown of this mech's weapons (the 蒼 crash = out-of-range weapon index).
 */
public final class AiEditor extends JPanel implements Reloadable {
    /** {key, 日本語名, confidence, tip} */
    static final String[][] COMMON = {
            {"activationProbability", "発動確率", "LIKELY", "CPU がこのイベントを選ぶ重み（相対値）"},
            {"activationProbabilityWhenCounter", "発動確率（反撃時）", "LIKELY", ""},
            {"activationRangeMin", "発動範囲 Min", "LIKELY", "標的までの距離（-999 = 無制限）"},
            {"activationRangeMax", "発動範囲 Max", "LIKELY", "標的までの距離（-999 = 無制限）"},
            {"activationAngleRangeMin", "角度範囲 Min", "LIKELY", "標的の方向（度）"},
            {"activationAngleRangeMax", "角度範囲 Max", "LIKELY", ""},
            {"activationAltitudeMin", "発動高度 Min", "LIKELY", "高低差（-999 = 無制限）"},
            {"activationAltitudeMax", "発動高度 Max", "LIKELY", ""},
            {"activationDurabilityMinPercentage", "発動耐久力 Min(%)", "LIKELY", "自機の残り耐久力 %"},
            {"activationDurabilityMaxPercentage", "発動耐久力 Max(%)", "LIKELY", ""},
            {"activationHeatMin", "発動熱量 Min", "LIKELY", "自機の蓄積熱量"},
            {"activationHeatMax", "発動熱量 Max", "LIKELY", ""},
    };
    static final String[][] MOVE = {
            {"moveType", "移動タイプ", "LIKELY", ""}, {"moveSpeed", "移動速度(/10)", "LIKELY", ""}, {"moveInertia", "移動慣性", "LIKELY", ""},
            {"moveTargetType", "移動目標タイプ", "LIKELY", ""}, {"moveTargetAngleCorrection", "移動目標角度補正", "LIKELY", ""},
            {"viewpointType", "視点タイプ", "LIKELY", ""}, {"viewpointAngleCorrection", "視点角度補正", "LIKELY", ""},
            {"jumpType", "ジャンプタイプ", "LIKELY", ""}, {"ascentVar1", "上昇処理用変数1", "LIKELY", ""}, {"ascentVar2", "上昇処理用変数2", "LIKELY", ""},
            {"genericFlag", "汎用フラグ", "LIKELY", ""}, {"attackProbabilityCorrection", "攻撃確率補正", "LIKELY", ""},
    };
    /** Unnamed members with the candidate names of the exe property list (order based guesses only). */
    static final String[][] RAW = {
            {"short1", "short1（データタイプ? 移動=1/攻撃=2）"}, {"int1", "int1（優先順位?）"}, {"int2", "int2（必要ＣＰＵレベル?）"},
            {"int5", "int5（標的外時の発動確率補正? 初期値100）"}, {"int14", "int14"}, {"int15", "int15"},
            {"int18", "int18（有効フレーム?）"}, {"int19", "int19"}, {"int20", "int20（終了範囲?）"}, {"int21", "int21"}, {"int22", "int22"}, {"int23", "int23"},
            {"int24", "int24"}, {"int25", "int25（ビットフラグ）"}, {"int26", "int26"}, {"short2", "short2"}, {"int27", "int27"}, {"int28", "int28"},
            {"short3", "short3"}, {"short4", "short4"},
    };

    final Ctx ctx;
    final Project.Mech mech;
    final FileDoc mek;
    final Consumer<Structure.Result> onStruct;
    final DefaultListModel<String> typeModel = new DefaultListModel<>();
    final JList<String> types = new JList<>(typeModel);
    final EvModel evModel = new EvModel();
    final JTable events = new JTable(evModel);
    final JPanel form = new JPanel(new BorderLayout());
    final JPanel typeForm = new JPanel(new BorderLayout());
    int curType = 0, curEv = -1;
    boolean loading;
    Ctx formCtx, typeCtx;
    JsonNode builtEv, builtTypeNode;

    public AiEditor(Ctx ctx, Project.Mech mech, FileDoc mek, Consumer<Structure.Result> onStruct) {
        super(new BorderLayout(4, 4));
        this.ctx = ctx; this.mech = mech; this.mek = mek; this.onStruct = onStruct;
        types.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        types.setVisibleRowCount(4);
        types.addListSelectionListener(e -> { if (!loading && !e.getValueIsAdjusting() && types.getSelectedIndex() >= 0) { curType = types.getSelectedIndex(); curEv = -1; reload(); } });
        events.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        events.getSelectionModel().addListSelectionListener(e -> { if (!loading && !e.getValueIsAdjusting()) { curEv = events.getSelectedRow(); showForm(); } });
        int[] cw = {28, 40, 200, 50, 90, 80};
        for (int i = 0; i < cw.length; i++) events.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);
        JPanel top = new JPanel(new BorderLayout(4, 2));
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        tb.add(btn("AI 種類を末尾に複製", () -> onStruct.accept(new Structure(ctx.p).aiTypeOp(mech, curType, false))));
        tb.add(btn("末尾の AI 種類を削除", () -> onStruct.accept(new Structure(ctx.p).aiTypeOp(mech, curType, true))));
        top.add(new JLabel("AI 種類（スクリプトの LoadMek が番号で選ぶ。[0] は通常）"), BorderLayout.NORTH);
        top.add(new JScrollPane(types), BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout());
        east.add(tb, BorderLayout.NORTH);
        east.add(typeForm, BorderLayout.CENTER);
        top.add(east, BorderLayout.EAST);
        JPanel mid = new JPanel(new BorderLayout(2, 2));
        JPanel eb = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        eb.add(btn("攻撃を追加", () -> evOp("addAttack", 0)));
        eb.add(btn("移動を追加", () -> evOp("addMove", 0)));
        eb.add(btn("複製", () -> evOp("dup", 0)));
        eb.add(btn("削除", () -> evOp("del", 0)));
        eb.add(btn("↑", () -> evOp("move", -1)));
        eb.add(btn("↓", () -> evOp("move", 1)));
        mid.add(eb, BorderLayout.NORTH);
        JScrollPane es = new JScrollPane(events);
        es.setPreferredSize(new Dimension(420, 230));
        mid.add(es, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout(2, 6));
        north.add(top, BorderLayout.NORTH);
        north.add(mid, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        ctx.add(this);
    }

    static JButton btn(String t, Runnable r) {
        JButton b = new JButton(t);
        b.setMargin(new Insets(1, 4, 1, 4));
        b.addActionListener(e -> r.run());
        return b;
    }

    void evOp(String kind, int dir) {
        Structure.Result r = new Structure(ctx.p).aiEventOp(mech, curType, kind, curEv, dir);
        if (r.ok && r.selPtr != null) curEv = Integer.parseInt(r.selPtr.substring(r.selPtr.lastIndexOf('/') + 1));
        onStruct.accept(r);
        reload();
    }

    JsonNode aiList() { return mek.tree.path("mekAiInfoList"); }
    JsonNode evList() { return aiList().path(curType).path("cpuEventList"); }
    String evPtr(int j) { return "/mekAiInfoList/" + curType + "/cpuEventList/" + j; }

    String weaponLabel(int w) {
        JsonNode wm = mek.tree.path("mekWeaponInfoMap");
        if (w < 0 || w >= wm.size()) return "#" + w + " ⚠ 範囲外（武装 " + wm.size() + " 個）";
        JsonNode x = wm.path("" + w);
        return "#" + w + " " + x.path("weaponName").asText() + (x.path("weaponIdentifier").asInt() == 2 ? "（非表示）" : "");
    }

    @Override public void reload() {
        loading = true;
        try {
            JsonNode ai = aiList();
            typeModel.clear();
            for (int i = 0; i < ai.size(); i++) typeModel.addElement("[" + i + "] " + ai.get(i).path("aiTypeJapanese").asText() + " / " + ai.get(i).path("aiTypeEnglish").asText() + "  （" + ai.get(i).path("cpuEventList").size() + " イベント）");
            if (curType >= ai.size()) curType = Math.max(0, ai.size() - 1);
            if (ai.size() > 0) types.setSelectedIndex(curType);
            JsonNode tn = ai.size() > 0 ? ai.get(curType) : null;
            if (tn != builtTypeNode) {
            builtTypeNode = tn;
            typeForm.removeAll();
            if (typeCtx != null) ctx.binds.removeAll(typeCtx.binds);
            typeCtx = new Ctx(ctx.p, ctx.nav);
            if (ai.size() > 0) {
                Grid g = new Grid();
                String tp = "/mekAiInfoList/" + curType;
                g.add(new JLabel("名前"), typeCtx.add(new StrField(typeCtx, mek, tp + "/aiTypeJapanese", "AI 名", true, 10)));
                g.add(new JLabel("英字名"), typeCtx.add(new StrField(typeCtx, mek, tp + "/aiTypeEnglish", "AI 英字名", true, 10)));
                typeForm.add(g, BorderLayout.CENTER);
            }
            ctx.binds.addAll(typeCtx.binds);
            typeForm.revalidate();
            }
            evModel.fireTableDataChanged();
            int n = evList().size();
            if (curEv >= n) curEv = n - 1;
            if (curEv >= 0) events.getSelectionModel().setSelectionInterval(curEv, curEv);
        } finally { loading = false; }
        JsonNode list = evList();
        JsonNode now = curEv >= 0 && curEv < list.size() ? list.get(curEv) : null;
        if (now != builtEv || now == null) showForm();
    }

    void showForm() {
        form.removeAll();
        if (formCtx != null) ctx.binds.removeAll(formCtx.binds);
        formCtx = new Ctx(ctx.p, ctx.nav);
        JsonNode list = evList();
        builtEv = curEv >= 0 && curEv < list.size() ? list.get(curEv) : null;
        if (curEv < 0 || curEv >= list.size()) {
            form.add(Form.note("イベントを選ぶと編集できます。-999 は「無制限」。\n攻撃イベントの武装は、この機体の武器リストの番号です（範囲外だと敵として出たときに落ちます＝蒼の不具合）。"), BorderLayout.NORTH);
        } else {
            String ep = evPtr(curEv);
            JsonNode e = list.get(curEv);
            JPanel box = Form.vbox();
            int type = e.path("type").asInt();
            box.add(Form.note("イベント " + curEv + " — " + (type == 2 ? "攻撃（CCpuEventAttack）" : type == 1 ? "移動（CCpuEventMove）" : "type " + type)
                    + "\n条件: " + (e.path("bsdxInfoCollectionList").size() > 0 ? EventText.termText(ctx.p, e.path("bsdxInfoCollectionList").get(0)) : "-")));
            if (type == 2) {
                Grid g = new Grid();
                LinkedHashMap<Integer, String> ws = new LinkedHashMap<>();
                JsonNode wm = mek.tree.path("mekWeaponInfoMap");
                for (int i = 0; i < wm.size(); i++) ws.put(i, weaponLabel(i).substring(("#" + i + " ").length()));
                g.add(Form.label("武装（技リスト番号）", "CONFIRMED", "mekWeaponInfoMapNo: 武器リスト → wazSequence → 技", "mekWeaponInfoMapNo"), formCtx.add(new IntCombo(formCtx, mek, ep + "/mekWeaponInfoMapNo", "AI 攻撃 武装", ws)));
                box.add(Form.titled("攻撃", g));
            } else if (type == 1) {
                box.add(Form.titled("移動", grid(ep, MOVE)));
            }
            box.add(Form.titled("発動条件（-999 = 無制限）", grid(ep, COMMON)));
            if (e.path("bsdxInfoCollectionList").size() > 0) box.add(Form.titled("状態の条件（Term）", Editors.termEditor(formCtx, mek, ep + "/bsdxInfoCollectionList/0")));
            Grid raw = new Grid();
            for (String[] f : RAW) if (e.has(f[0])) raw.add(Form.label(f[1], "UNKNOWN", "名前は exe のプロパティ表の並びからの推測（未確認）", f[0]), formCtx.add(new IntField(formCtx, mek, ep + "/" + f[0], "AI " + f[0])));
            box.add(Form.titled("詳細(raw)（意味未確認）", raw));
            form.add(Editors.scroll(box), BorderLayout.CENTER);
        }
        ctx.binds.addAll(formCtx.binds);
        form.revalidate();
        form.repaint();
    }

    Grid grid(String ep, String[][] spec) {
        Grid g = new Grid();
        JsonNode e = Json.at(mek.tree, ep);
        for (String[] f : spec) if (e != null && e.has(f[0])) g.add(Form.label(f[1], f[2], f[0] + (f[3].isEmpty() ? "" : "\n" + f[3]), f[0]), formCtx.add(new IntField(formCtx, mek, ep + "/" + f[0], "AI " + f[1])));
        return g;
    }

    static String range(JsonNode e, String a, String b) {
        int x = e.path(a).asInt(-999), y = e.path(b).asInt(-999);
        return (x == -999 ? "∞" : "" + x) + "〜" + (y == -999 ? "∞" : "" + y);
    }

    final class EvModel extends AbstractTableModel {
        final String[] cols = {"#", "種類", "武装 / 移動タイプ", "確率", "範囲", "高度"};
        public int getRowCount() { return evList().size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            JsonNode e = evList().get(r);
            if (e == null) return "";
            int type = e.path("type").asInt();
            switch (c) {
                case 0: return r;
                case 1: return type == 2 ? "攻撃" : type == 1 ? "移動" : "" + type;
                case 2: return type == 2 ? weaponLabel(e.path("mekWeaponInfoMapNo").asInt()) : "移動タイプ " + e.path("moveType").asInt() + " / 速度 " + e.path("moveSpeed").asInt();
                case 3: return e.path("activationProbability").asInt();
                case 4: return range(e, "activationRangeMin", "activationRangeMax");
                default: return range(e, "activationAltitudeMin", "activationAltitudeMax");
            }
        }
    }

    // test hooks
    public JTable table() { return events; }
    public void selectEvent(int j) { curEv = j; reload(); }
}
