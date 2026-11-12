package bsdx.workshop.ui;

import bsdx.workshop.data.FileDoc;
import bsdx.workshop.data.Project;
import bsdx.workshop.data.WazNav;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * v5 left outline of the current mech (機体ステータス / AI 表 / 武装 → 技 / 基本動作の技 / ほかの技)
 * or of a shared waz (its skills). One list that shows how weapons, skills and the mech relate.
 */
public final class Outline extends JPanel {
    public static final int GROUP = 0, STATUS = 1, AI = 2, WEAPON = 3, SKILL = 4;

    public static final class Item {
        public final int kind; public final String key; public final int si; final String group;
        Item(int kind, String key, int si, String group) { this.kind = kind; this.key = key; this.si = si; this.group = group; }
        boolean same(Item o) { return o != null && o.kind == kind && Objects.equals(o.key, key) && o.si == si; }
    }

    final Project p;
    final JTree tree = new JTree(new DefaultMutableTreeNode());
    final JLabel title = new JLabel(" ");
    FileDoc mek, waz;
    int wazIdx = -1;
    Project.Mech mech;
    Consumer<Item> onSelect;
    /** Right click on an item (already selected). */
    java.util.function.BiConsumer<Item, java.awt.event.MouseEvent> onContext;
    boolean silent;

    public Outline(Project p) {
        super(new BorderLayout());
        this.p = p;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                setIcon(null);
                Object uo = ((DefaultMutableTreeNode) v).getUserObject();
                if (uo instanceof Item) setText(label((Item) uo, sel));
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (silent || onSelect == null) return;
            Item it = selected();
            if (it != null && it.kind != GROUP) onSelect.accept(it);
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            void popup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger() || onContext == null) return;
                TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
                if (tp == null) return;
                tree.setSelectionPath(tp);
                Item it = selected();
                if (it != null && it.kind != GROUP) onContext.accept(it, e);
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }
        });
        title.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createEmptyBorder());
        add(sp, BorderLayout.CENTER);
    }

    static String esc(String s) { return Form.esc(s == null ? "" : s); }

    String label(Item it, boolean sel) {
        String gray = sel ? "" : "<font color='#8a8f99'>", gEnd = sel ? "" : "</font>";
        String chg = sel ? "" : "<font color='#d97a00'>";
        switch (it.kind) {
            case GROUP: return "<html><b>" + esc(it.group) + "</b></html>";
            case STATUS: return "<html>機体ステータス" + (changed("/mekBasicInfo") ? " " + chg + "（変更あり）" + gEnd : "") + "</html>";
            case AI: return "<html>AI 表 " + gray + "（" + (mek == null ? 0 : mek.tree.path("mekAiInfoList").size()) + " 種類）" + gEnd + (changed("/mekAiInfoList") ? " " + chg + "（変更あり）" + gEnd : "") + "</html>";
            case WEAPON: {
                JsonNode w = mek.tree.path("mekWeaponInfoMap").path(it.key);
                int si = w.path("wazSequence").asInt();
                String hits = waz == null ? "" : EventText.hitsSummary(p, waz.tree, si);
                boolean ch = changed("/mekWeaponInfoMap/" + it.key + "/") || waz != null && skillChanged(si);
                return "<html>" + it.key + "　" + esc(w.path("weaponName").asText()) + (ch ? " " + chg + "●" + gEnd : "")
                        + "　" + gray + "技 " + si + (hits.isEmpty() || hits.equals("-") ? "" : "　" + esc(shorten(hits, 26))) + gEnd + "</html>";
            }
            default: {
                String name = waz == null ? "?" : WazNav.skillName(waz.tree, it.si);
                return "<html>技 " + it.si + "　" + esc(name) + (waz != null && skillChanged(it.si) ? " " + chg + "●" + gEnd : "") + "</html>";
            }
        }
    }

    static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    boolean changed(String prefix) {
        if (mek == null) return false;
        for (String k : mek.changes().keySet()) if (k.startsWith(prefix)) return true;
        return false;
    }

    boolean skillChanged(int si) {
        String pre = WazNav.skillPtr(si) + "/";
        for (String k : waz.changes().keySet()) if (k.startsWith(pre)) return true;
        return false;
    }

    /** Mech mode: status, AI, weapons → skills, basic-motion skills, other skills. */
    public void setMech(Project.Mech m, FileDoc mek, FileDoc waz) {
        this.mech = m; this.mek = mek; this.waz = waz; this.wazIdx = m == null ? -1 : m.wazIdx;
        title.setText(m == null ? " " : m.mekName + "（" + m.pilot + "）");
        rebuild();
    }

    /** Shared-waz mode: the skills of a waz no mech owns. */
    public void setShared(int wazIdx, FileDoc waz) {
        this.mech = null; this.mek = null; this.waz = waz; this.wazIdx = wazIdx;
        title.setText("共通: " + p.wazLabel(wazIdx));
        rebuild();
    }

    public void clear() { mech = null; mek = null; waz = null; wazIdx = -1; title.setText(" "); rebuild(); }

    static List<String> weaponKeys(FileDoc mek) {
        List<String> ks = new ArrayList<>();
        mek.tree.path("mekWeaponInfoMap").fieldNames().forEachRemaining(ks::add);
        ks.sort(Comparator.comparingInt(k -> { try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 9999; } }));
        return ks;
    }

    /** Skills of the basic-motion table (mekPairBlock entries with int1 == -1). */
    static Set<Integer> basicSkills(FileDoc mek) {
        Set<Integer> s = new TreeSet<>();
        JsonNode pairs = mek.tree.path("mekPairBlock").path("unkPair");
        for (JsonNode pr : pairs) if (pr.path("int1").asInt() == -1 && pr.path("int2").asInt() >= 0) s.add(pr.path("int2").asInt());
        return s;
    }

    public void rebuild() {
        Item keep = selected();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        List<DefaultMutableTreeNode> groups = new ArrayList<>();
        int nSkills = waz == null ? 0 : waz.tree.path("skillList").size();
        if (mek != null) {
            root.add(node(new Item(STATUS, null, -1, null)));
            root.add(node(new Item(AI, null, -1, null)));
            List<String> ks = weaponKeys(mek);
            Set<Integer> used = new HashSet<>();
            DefaultMutableTreeNode wg = node(new Item(GROUP, null, -1, "武装（" + ks.size() + "）"));
            for (String k : ks) { wg.add(node(new Item(WEAPON, k, -1, null))); used.add(mek.tree.path("mekWeaponInfoMap").path(k).path("wazSequence").asInt()); }
            root.add(wg); groups.add(wg);
            Set<Integer> basic = basicSkills(mek);
            basic.removeIf(si -> si >= nSkills);
            if (!basic.isEmpty()) {
                DefaultMutableTreeNode bg = node(new Item(GROUP, null, -1, "基本動作の技（" + basic.size() + "）"));
                for (int si : basic) bg.add(node(new Item(SKILL, null, si, null)));
                root.add(bg); groups.add(bg);
            }
            used.addAll(basic);
            List<Integer> other = new ArrayList<>();
            for (int si = 0; si < nSkills; si++) if (!used.contains(si)) other.add(si);
            if (!other.isEmpty()) {
                DefaultMutableTreeNode og = node(new Item(GROUP, null, -1, "ほかの技（派生先・弾など " + other.size() + "）"));
                for (int si : other) og.add(node(new Item(SKILL, null, si, null)));
                root.add(og); groups.add(og);
            }
        } else if (waz != null) {
            DefaultMutableTreeNode sg = node(new Item(GROUP, null, -1, "技（" + nSkills + "）"));
            for (int si = 0; si < nSkills; si++) sg.add(node(new Item(SKILL, null, si, null)));
            root.add(sg); groups.add(sg);
        }
        silent = true;
        try {
            tree.setModel(new DefaultTreeModel(root));
            for (DefaultMutableTreeNode g : groups) tree.expandPath(new TreePath(g.getPath()));
            if (keep != null) select(keep.kind, keep.key, keep.si);
        } finally { silent = false; }
    }

    static DefaultMutableTreeNode node(Item it) { return new DefaultMutableTreeNode(it); }

    public Item selected() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        Object uo = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        return uo instanceof Item ? (Item) uo : null;
    }

    /** Selects an item without firing onSelect (keeps the list in sync with navigation elsewhere). Skills prefer their weapon. */
    public void select(int kind, String key, int si) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        DefaultMutableTreeNode hit = null;
        for (Enumeration<TreeNode> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
            if (!(n.getUserObject() instanceof Item)) continue;
            Item it = (Item) n.getUserObject();
            if (it.kind == kind && Objects.equals(it.key, key) && it.si == si) { hit = n; break; }
        }
        boolean was = silent;
        silent = true;
        try {
            if (hit == null) tree.clearSelection();
            else {
                TreePath tp = new TreePath(hit.getPath());
                tree.setSelectionPath(tp);
                Rectangle r = tree.getPathBounds(tp);
                if (r != null) tree.scrollRectToVisible(new Rectangle(0, r.y, 1, r.height));
            }
        } finally { silent = was; }
    }

    /** Selects the weapon that uses skill si if any, else the skill item (no onSelect). */
    public void selectSkill(int si, String weaponKey) {
        if (weaponKey != null && mek != null && mek.tree.path("mekWeaponInfoMap").path(weaponKey).path("wazSequence").asInt(-1) == si) { select(WEAPON, weaponKey, -1); return; }
        select(SKILL, null, si);
        if (selected() == null && mek != null)
            for (String k : weaponKeys(mek)) if (mek.tree.path("mekWeaponInfoMap").path(k).path("wazSequence").asInt() == si) { select(WEAPON, k, -1); return; }
    }

    public void repaintLabels() { tree.repaint(); }
}
