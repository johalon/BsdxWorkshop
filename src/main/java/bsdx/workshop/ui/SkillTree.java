package bsdx.workshop.ui;

import bsdx.workshop.data.FileDoc;
import bsdx.workshop.data.Json;
import bsdx.workshop.data.Project;
import bsdx.workshop.data.WazNav;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.util.function.Consumer;

/** waz → 技 → ターン → スロット → イベント. Labels are computed live from the JSON tree. */
public final class SkillTree extends JPanel {
    public static final class N {
        public final int kind; // 0 waz, 1 skill, 2 turn, 3 slot, 4 event
        public final int si, t, u, k;
        public final String ptr;
        N(int kind, int si, int t, int u, int k, String ptr) { this.kind = kind; this.si = si; this.t = t; this.u = u; this.k = k; this.ptr = ptr; }
    }

    final Project p;
    final JTree tree = new JTree(new DefaultMutableTreeNode("（機体を選んでください）"));
    final JTextArea banner = Form.note("");
    final JTextField filter = new JTextField();
    FileDoc doc;
    int wazIdx = -1;
    Project.Mech mech;
    Consumer<N> onSelect;
    boolean silent;

    public SkillTree(Project p) {
        super(new BorderLayout());
        this.p = p;
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        ToolTipManager.sharedInstance().registerComponent(tree);      // v4: slot nodes show their explanation
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                Object uo = ((DefaultMutableTreeNode) v).getUserObject();
                if (uo instanceof N) {
                    N n = (N) uo;
                    setText(label(n));
                    if (doc != null && changedUnder(n) && !sel) setForeground(new Color(180, 90, 0));
                    setToolTipText(n.kind == 3 && doc != null ? Form.html(p.fields.slotTip(doc.tree.at(n.ptr + "/unitQuantity").asInt())) : null);
                }
                return this;
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent e) { ensureBuilt((DefaultMutableTreeNode) e.getPath().getLastPathComponent()); }
            @Override public void treeWillCollapse(TreeExpansionEvent e) {}
        });
        tree.addTreeSelectionListener(e -> {
            if (silent || onSelect == null) return;
            TreePath tp = e.getNewLeadSelectionPath();
            if (tp == null) return;
            Object uo = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
            if (uo instanceof N) onSelect.accept((N) uo);
        });
        JPanel top = new JPanel(new BorderLayout());
        top.add(banner, BorderLayout.CENTER);
        JPanel f = new JPanel(new BorderLayout());
        f.add(new JLabel(" 技名で絞り込み "), BorderLayout.WEST);
        f.add(filter, BorderLayout.CENTER);
        top.add(f, BorderLayout.SOUTH);
        filter.addActionListener(e -> rebuild());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    boolean changedUnder(N n) {
        if (n.kind == 0) return doc.isDirty();
        String pre = n.kind == 1 ? WazNav.skillPtr(n.si) + "/" : n.ptr + "/";
        for (String k : doc.changes().keySet()) if (k.startsWith(pre) || k.equals(n.ptr)) return true;
        return false;
    }

    String label(N n) {
        if (doc == null) return "";
        switch (n.kind) {
            case 0: return doc.name + "（" + p.wazLabel(wazIdx) + "）" + (doc.isDirty() ? " *変更あり" : "");
            case 1: return "技 " + n.si + ": " + WazNav.skillName(doc.tree, n.si);
            case 2: return "ターン " + n.t + "（" + WazNav.turnLength(doc.tree, n.si, n.t) + "F）";
            case 3: {
                int slot = doc.tree.at(n.ptr + "/unitQuantity").asInt();
                return "[" + slot + "] " + p.fields.slotName(slot) + "  ×" + doc.tree.at(n.ptr + "/skillInfoObjectList").size();
            }
            default: {
                JsonNode o = Json.at(doc.tree, n.ptr);
                if (o == null) return "?";
                String s = EventText.summary(p, o, mech == null ? -1 : mech.spmIdx);
                return String.format("%d-%d  %s", o.path("startFrame").asInt(), o.path("endFrame").asInt(), s);
            }
        }
    }

    public void setWaz(FileDoc doc, int wazIdx, Project.Mech mech, String bannerText) {
        this.doc = doc; this.wazIdx = wazIdx; this.mech = mech;
        banner.setText(bannerText == null ? "" : bannerText);
        banner.setForeground(bannerText != null && bannerText.startsWith("⚠") ? new Color(170, 30, 0) : Color.DARK_GRAY);
        rebuild();
    }

    void rebuild() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new N(0, -1, -1, -1, -1, ""));
        if (doc != null) {
            String q = filter.getText().trim();
            JsonNode sl = doc.tree.path("skillList");
            for (int si = 0; si < sl.size(); si++) {
                if (!q.isEmpty() && !WazNav.skillName(doc.tree, si).contains(q) && !String.valueOf(si).equals(q)) continue;
                DefaultMutableTreeNode sn = new DefaultMutableTreeNode(new N(1, si, -1, -1, -1, WazNav.skillPtr(si)));
                if (sl.get(si).path("phasesInfo").size() > 0) sn.add(new DefaultMutableTreeNode("…"));
                root.add(sn);
            }
        }
        tree.setModel(new DefaultTreeModel(root));
    }

    void ensureBuilt(DefaultMutableTreeNode sn) {
        if (!(sn.getUserObject() instanceof N) || ((N) sn.getUserObject()).kind != 1) return;
        if (sn.getChildCount() != 1 || sn.getFirstChild() instanceof DefaultMutableTreeNode && ((DefaultMutableTreeNode) sn.getFirstChild()).getUserObject() instanceof N) return;
        sn.removeAllChildren();
        int si = ((N) sn.getUserObject()).si;
        JsonNode ph = doc.tree.at(WazNav.skillPtr(si) + "/phasesInfo");
        for (int t = 0; t < ph.size(); t++) {
            String tp = WazNav.skillPtr(si) + "/phasesInfo/" + t;
            DefaultMutableTreeNode tn = new DefaultMutableTreeNode(new N(2, si, t, -1, -1, tp));
            JsonNode units = ph.get(t).path("skillUnitCollection");
            for (int u = 0; u < units.size(); u++) {
                String up = tp + "/skillUnitCollection/" + u;
                DefaultMutableTreeNode un = new DefaultMutableTreeNode(new N(3, si, t, u, -1, up));
                JsonNode objs = units.get(u).path("skillInfoObjectList");
                for (int k = 0; k < objs.size(); k++) un.add(new DefaultMutableTreeNode(new N(4, si, t, u, k, up + "/skillInfoObjectList/" + k)));
                tn.add(un);
            }
            sn.add(tn);
        }
        ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(sn);
    }

    DefaultMutableTreeNode skillNode(int si) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
            if (((N) c.getUserObject()).si == si) return c;
        }
        return null;
    }

    /** Rebuilds after a structural change and re-selects the closest node to the previous selection. */
    public void rebuildKeep() {
        N sel = selected();
        rebuild();
        if (sel == null || doc == null) return;
        if (sel.kind == 0) { tree.setSelectionRow(0); return; }
        int n = doc.tree.path("skillList").size();
        if (n == 0) return;
        select(Math.min(sel.si, n - 1), sel.t, sel.u, sel.k);
    }

    /** Selects the node of an event / unit / turn / skill pointer ("/skillList/si/phasesInfo/t/skillUnitCollection/u/skillInfoObjectList/k"). */
    public void selectPtr(String ptr) {
        if (ptr == null) return;
        String[] a = ptr.split("/");
        int si = a.length > 2 ? Integer.parseInt(a[2]) : -1;
        int t = a.length > 4 ? Integer.parseInt(a[4]) : -1;
        int u = a.length > 6 ? Integer.parseInt(a[6]) : -1;
        int k = a.length > 8 ? Integer.parseInt(a[8]) : -1;
        if (si >= 0) select(si, t, u, k);
    }

    /** Selects a node; t/u/k = -1 for coarser levels. Fires onSelect. */
    public void select(int si, int t, int u, int k) {
        if (doc != null && si >= doc.tree.path("skillList").size()) si = doc.tree.path("skillList").size() - 1;
        if (si < 0) return;
        DefaultMutableTreeNode n = skillNode(si);
        if (n == null) { filter.setText(""); rebuild(); n = skillNode(si); }
        if (n == null) return;
        if (t >= 0) {
            ensureBuilt(n);
            if (n.getChildCount() > 0 && n.getFirstChild() instanceof DefaultMutableTreeNode && ((DefaultMutableTreeNode) n.getFirstChild()).getUserObject() instanceof N) {
                n = (DefaultMutableTreeNode) n.getChildAt(Math.min(t, n.getChildCount() - 1));
                if (u >= 0 && n.getChildCount() > 0) {
                    n = (DefaultMutableTreeNode) n.getChildAt(Math.min(u, n.getChildCount() - 1));
                    if (k >= 0 && n.getChildCount() > 0) n = (DefaultMutableTreeNode) n.getChildAt(Math.min(k, n.getChildCount() - 1));
                }
            }
        }
        TreePath tp = new TreePath(n.getPath());
        tree.setSelectionPath(tp);
        tree.scrollPathToVisible(tp);
    }

    public String docName() { return doc == null ? "" : doc.name; }

    public N selected() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        Object uo = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        return uo instanceof N ? (N) uo : null;
    }
}
