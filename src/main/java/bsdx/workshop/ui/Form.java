package bsdx.workshop.ui;

import bsdx.workshop.data.FileDoc;
import bsdx.workshop.data.Fields;
import bsdx.workshop.data.Json;
import bsdx.workshop.data.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Form building blocks bound to JSON pointers of a FileDoc. All edits go through the project's UndoStack. */
public final class Form {
    private Form() {}

    public static final Color CHANGED = new Color(255, 236, 160);
    /** v5: mark for item names that are not the game's own wording (not found in BaldrSky.exe). */
    public static final String COINED_MARK = "*", COINED_HEX = "#0e8a8a";
    public static final String COINED_NOTE = "* = ゲーム（BaldrSky.exe）には無い名前です。このツールで付けました。";
    public static final Color LIKELY = new Color(150, 90, 0);
    public static final Color UNKNOWN = new Color(120, 120, 120);
    static final Charset CP932 = Charset.forName("windows-31j");

    /** Something that re-reads its value from the tree (after undo / redo / other edits). */
    public interface Reloadable { void reload(); }

    /** Context of one property page. */
    public static final class Ctx {
        public final Project p;
        public final List<Reloadable> binds = new ArrayList<>();
        public final Nav nav;
        public Ctx(Project p, Nav nav) { this.p = p; this.nav = nav; }
        public void reloadAll() { for (Reloadable r : new ArrayList<>(binds)) r.reload(); }
        public <T extends Reloadable> T add(T r) { binds.add(r); r.reload(); return r; }
    }

    /** Navigation callbacks into the main window. */
    public interface Nav {
        void openSkill(int wazIdx, int skill);
        void status(String msg);
        /** v3: the sprite preview of a skill / turn is showing global frame g of seq (timeline playhead). */
        default void playhead(FileDoc waz, int si, bsdx.workshop.data.SkillSeq seq, int g) {}
    }

    /** Integer text field: commit with Enter or when focus leaves. */
    public static final class IntField extends JTextField implements Reloadable {
        final Ctx ctx; final FileDoc doc; final String ptr; final String label; final boolean asLong;
        IntConsumer after;
        public IntField(Ctx ctx, FileDoc doc, String ptr, String label) {
            super(7);
            this.ctx = ctx; this.doc = doc; this.ptr = ptr; this.label = label;
            JsonNode n = Json.at(doc.tree, ptr);
            asLong = n != null && n.isLong();
            addActionListener(e -> commit());
            addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commit(); } });
            setToolTipText(ptr);
        }
        /** v5: never shrink below the preferred width (FlatLaf's minimum would hide the value in a tight grid). */
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        void commit() {
            JsonNode cur = Json.at(doc.tree, ptr);
            String t = getText().trim();
            if (cur != null && t.equals(cur.asText())) return;
            try {
                long v = Long.parseLong(t);
                JsonNode nv = asLong ? LongNode.valueOf(v) : IntNode.valueOf((int) v);
                if (!asLong && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)) throw new NumberFormatException();
                ctx.p.undo.set(doc, ptr, nv, label + " = " + v + "  (" + doc.name + ")");
                if (after != null) after.accept((int) v);
            } catch (NumberFormatException ex) {
                Toolkit.getDefaultToolkit().beep();
                ctx.nav.status("整数を入力してください: " + label);
                reload();
            }
        }
        @Override public void reload() {
            JsonNode n = Json.at(doc.tree, ptr);
            String s = n == null ? "" : n.asText();
            if (!s.equals(getText())) setText(s);
            setBackground(doc.isChanged(ptr) ? CHANGED : UIManager.getColor("TextField.background"));
        }
    }

    /** String field (windows-31j check, or UTF-8 for the English/roma names). */
    public static final class StrField extends JTextField implements Reloadable {
        final Ctx ctx; final FileDoc doc; final String ptr; final String label; final boolean sjis;
        public StrField(Ctx ctx, FileDoc doc, String ptr, String label, boolean sjis, int cols) {
            super(cols);
            this.ctx = ctx; this.doc = doc; this.ptr = ptr; this.label = label; this.sjis = sjis;
            addActionListener(e -> commit());
            addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commit(); } });
        }
        void commit() {
            String t = getText();
            JsonNode cur = Json.at(doc.tree, ptr);
            if (cur != null && t.equals(cur.asText())) return;
            if (sjis && !CP932.newEncoder().canEncode(t)) {
                Toolkit.getDefaultToolkit().beep();
                ctx.nav.status("Shift_JIS(windows-31j) で表せない文字があります: " + label);
                reload();
                return;
            }
            ctx.p.undo.set(doc, ptr, TextNode.valueOf(t), label + " を変更  (" + doc.name + ")");
        }
        @Override public void reload() {
            JsonNode n = Json.at(doc.tree, ptr);
            String s = n == null ? "" : n.asText();
            if (!s.equals(getText())) setText(s);
            setBackground(doc.isChanged(ptr) ? CHANGED : UIManager.getColor("TextField.background"));
        }
    }

    /** v5: multi-line (wrapped, 3 rows) text for long strings such as 解説. Enter = commit (no line breaks are added), Tab = next field. */
    public static final class StrArea extends JTextArea implements Reloadable {
        final Ctx ctx; final FileDoc doc; final String ptr; final String label; final boolean sjis;
        public StrArea(Ctx ctx, FileDoc doc, String ptr, String label, boolean sjis, int rows) {
            super(rows, 20);
            this.ctx = ctx; this.doc = doc; this.ptr = ptr; this.label = label; this.sjis = sjis;
            setLineWrap(true);
            setWrapStyleWord(false);
            setBorder(UIManager.getBorder("TextField.border"));
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "bsdx-commit");
            getActionMap().put("bsdx-commit", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { commit(); } });
            addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { commit(); } });
        }
        /** Keeps the row count fixed while the width follows the column (wrapping instead of widening). */
        @Override public Dimension getPreferredSize() { Dimension d = super.getPreferredSize(); Insets in = getInsets(); return new Dimension(80, Math.max(d.height, getRows() * getRowHeight() + in.top + in.bottom)); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        void commit() {
            String t = getText();
            JsonNode cur = Json.at(doc.tree, ptr);
            if (cur != null && t.equals(cur.asText())) return;
            if (sjis && !CP932.newEncoder().canEncode(t)) {
                Toolkit.getDefaultToolkit().beep();
                ctx.nav.status("Shift_JIS(windows-31j) で表せない文字があります: " + label);
                reload();
                return;
            }
            ctx.p.undo.set(doc, ptr, TextNode.valueOf(t), label + " を変更  (" + doc.name + ")");
        }
        @Override public void reload() {
            JsonNode n = Json.at(doc.tree, ptr);
            String s = n == null ? "" : n.asText();
            if (!s.equals(getText())) { setText(s); setCaretPosition(0); }
            setBackground(doc.isChanged(ptr) ? CHANGED : UIManager.getColor("TextField.background"));
        }
    }

    public static final class Item {
        public final int value; public final String label;
        public Item(int value, String label) { this.value = value; this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Combo box bound to an int value; unknown values are shown as "N (?)". */
    public static final class IntCombo extends JComboBox<Item> implements Reloadable {
        final Ctx ctx; final FileDoc doc; public final String ptr; final String label;
        boolean loading;
        IntConsumer after;
        public IntCombo(Ctx ctx, FileDoc doc, String ptr, String label, Map<Integer, String> labels) {
            this.ctx = ctx; this.doc = doc; this.ptr = ptr; this.label = label;
            setItems(labels);
            addActionListener(e -> {
                if (loading) return;
                Item it = (Item) getSelectedItem();
                if (it == null) return;
                if (ctx.p.undo.set(doc, ptr, IntNode.valueOf(it.value), label + " = " + it.label + "  (" + doc.name + ")") && after != null) after.accept(it.value);
            });
        }
        public void setItems(Map<Integer, String> labels) {
            loading = true;
            removeAllItems();
            for (Map.Entry<Integer, String> e : labels.entrySet()) addItem(new Item(e.getKey(), e.getKey() + ": " + e.getValue()));
            loading = false;
        }
        @Override public void reload() {
            loading = true;
            JsonNode n = Json.at(doc.tree, ptr);
            int v = n == null ? 0 : n.asInt();
            Item found = null;
            for (int i = 0; i < getItemCount(); i++) if (getItemAt(i).value == v) found = getItemAt(i);
            if (found == null) { found = new Item(v, v + " (?)"); addItem(found); }
            setSelectedItem(found);
            setBackground(doc.isChanged(ptr) ? CHANGED : UIManager.getColor("ComboBox.background"));
            loading = false;
        }
    }

    /** Label with confidence marking (LIKELY = "（推定）" orange, UNKNOWN = grey) and semantics tooltip. */
    public static JLabel label(String name, String confidence, String tip) { return label(name, confidence, tip, null); }

    /** v3: label + the raw field name (int1, int2 …) in small grey text right after it (always visible, not only on hover). */
    public static JLabel label(String name, String confidence, String tip, String raw) {
        String txt = name + ("LIKELY".equals(confidence) ? "（推定）" : "UNKNOWN".equals(confidence) ? "（不明）" : "");
        // v5: a name that is not in the game's exe was coined by this tool -> "*" mark (teal) + a line in the tooltip
        boolean coined = !bsdx.workshop.data.ExeTerms.isGameTerm(name);
        boolean rawShown = !(raw == null || raw.isEmpty() || raw.equals(name));
        JLabel l = new JLabel(!rawShown && !coined ? txt
                : "<html>" + esc(txt) + (coined ? "<font color='" + COINED_HEX + "'>" + COINED_MARK + "</font>" : "")
                + (rawShown ? " <font color='#8a8a8a' size='-2'>" + esc(raw) + "</font>" : "") + "</html>");
        l.putClientProperty("rawKey", raw);
        l.putClientProperty("coined", coined);
        if ("LIKELY".equals(confidence)) l.setForeground(LIKELY);
        if ("UNKNOWN".equals(confidence)) l.setForeground(UNKNOWN);
        String tt = (coined ? COINED_NOTE + "\n" : "") + (tip == null ? "" : tip);
        if (!tt.isEmpty()) l.setToolTipText(html(tt.trim()));
        return l;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** v4: plain tooltip text ("\n" = line break) as wrapped HTML. */
    public static String html(String tip) { return "<html><body style='width:340px'>" + esc(tip).replace("\n", "<br>") + "</body></html>"; }

    /** Two-column grid panel. */
    public static final class Grid extends JPanel {
        int row;
        public Grid() { super(new GridBagLayout()); setAlignmentX(LEFT_ALIGNMENT); }
        public void add(JComponent label, JComponent editor) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(1, 2, 1, 6);
            super.add(label, c);
            c.gridx = 1; c.weightx = 1;
            c.fill = editor instanceof IntField ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
            super.add(editor, c);
            row++;
        }
        /** v3: a small line under the editor of the previous row (value legend). */
        public void hint(JComponent comp) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 1; c.gridy = row++; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(0, 2, 3, 6);
            super.add(comp, c);
        }
        public void full(JComponent comp) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(2, 2, 2, 2);
            super.add(comp, c);
        }
    }

    public static JPanel titled(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));
        p.add(inner, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Adds an editor row for a dictionary field (int / enum) if the key exists in the object. */
    public static void fieldRow(Ctx ctx, Grid g, FileDoc doc, String objPtr, Fields.Field f, String context) {
        JsonNode o = Json.at(doc.tree, objPtr);
        if (o == null || !f.isScalarKey() || !o.has(f.key) || !o.get(f.key).isValueNode()) return;
        String ptr = Json.child(objPtr, f.key);
        String tip = Fields.tip(f.ux, f.uxConf, f.key + (f.unit.isEmpty() ? "" : " [" + f.unit + "]") + "\n" + f.semantics);
        JLabel l = label(f.nameJa + (f.unit.isEmpty() ? "" : "[" + f.unit + "]"), f.confidence, tip, f.key);
        if (o.get(f.key).isTextual()) {
            g.add(l, ctx.add(new StrField(ctx, doc, ptr, f.nameJa, true, 20)));
            return;
        }
        LinkedHashMap<Integer, String> en = f.enumLabels();
        if (!f.editable) {
            JTextField t = new JTextField(o.get(f.key).asText());
            t.setEditable(false);
            g.add(l, t);
        } else if (!en.isEmpty()) {
            g.add(l, ctx.add(new IntCombo(ctx, doc, ptr, context + f.nameJa, en)));
        } else {
            g.add(l, ctx.add(new IntField(ctx, doc, ptr, context + f.nameJa)));
        }
        Fields.Legend lg = f.legend();
        if (!lg.isEmpty()) g.hint(ctx.add(new LegendHint(doc, ptr, lg)));
    }

    /** v3: inline meaning of the value(s) of an enum / flag / special-value field, under its editor. */
    /** Wrapping small text whose preferred width never forces the form wider than the viewport. */
    public static class Wrap extends JTextArea {
        int lastH = -1;
        public Wrap(String t) {
            super(t);
            setEditable(false); setLineWrap(true); setWrapStyleWord(true); setOpaque(false);
            setFont(getFont().deriveFont(Math.max(10f, getFont().getSize2D() - 2f)));
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    int h = getPreferredSize().height;
                    if (h != lastH) { lastH = h; revalidate(); }
                }
            });
        }
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.min(d.width, 160), d.height);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(40, super.getPreferredSize().height); }
    }

    public static final class LegendHint extends Wrap implements Reloadable {
        final FileDoc doc; final String ptr; final Fields.Legend lg;
        public LegendHint(FileDoc doc, String ptr, Fields.Legend lg) {
            super("");
            this.doc = doc; this.ptr = ptr; this.lg = lg;
            setForeground(lg.guessed ? LIKELY : new Color(95, 95, 110));
        }
        public static String text(Fields.Legend lg, JsonNode v) {
            StringBuilder sb = new StringBuilder();
            if (v != null && v.isNumber() && (lg.bits || lg.special)) {
                String d = lg.describe(v.asLong());
                if (!d.isEmpty()) sb.append("現在 ").append(v.asLong()).append(" = ").append(d).append("　");
            } else if (v != null && v.isNumber() && !lg.values.isEmpty() && !lg.values.containsKey(v.asInt())) {
                sb.append("現在 ").append(v.asLong()).append(" = 一覧に無い値　");
            }
            if (!lg.values.isEmpty()) sb.append(lg.bits ? "ビット: " : lg.special ? "特別な値: " : "値: ").append(lg.legendText());
            if (lg.guessed) sb.append("（推定）");
            if (!lg.note.isEmpty()) sb.append(sb.length() > 0 ? "　※" : "※").append(lg.note);
            return sb.toString();
        }
        @Override public void reload() {
            String t = text(lg, Json.at(doc.tree, ptr));
            if (!t.equals(getText())) setText(t);
        }
    }

    /** v3: static legend line for hand-made combos (威力の種類, 処理タイプ, 武装カテゴリ …). */
    public static JTextArea legendNote(Map<Integer, String> values, boolean guessed) {
        StringBuilder sb = new StringBuilder("値: ");
        int n = 0;
        for (Map.Entry<Integer, String> e : values.entrySet()) sb.append(n++ > 0 ? " / " : "").append(e.getKey()).append('=').append(e.getValue());
        if (guessed) sb.append("（推定）");
        Wrap a = new Wrap(sb.toString());
        a.setForeground(guessed ? LIKELY : new Color(95, 95, 110));
        return a;
    }

    public static JTextArea note(String s) {
        JTextArea a = new JTextArea(s);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        return a;
    }

    public static JPanel vbox() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }
}
