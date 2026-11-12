package bsdx.workshop.ui;

import bsdx.workshop.data.FileDoc;
import bsdx.workshop.data.Project;
import bsdx.workshop.data.SkillSeq;
import bsdx.workshop.data.WazNav;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Frame timeline. v3: two modes on one frame axis (data: {@link SkillSeq}):
 * <ul><li>skill mode (技を選んだとき): ALL turns appended left to right, ターン0 | ターン1 | …, each with its own ruler from 0,
 * jumps ignored;</li><li>turn mode (ターンを選んだとき): that turn only.</li></ul>
 * Rows = slots ([0] スプライト, [18] ベクトル：速度 …). One click on a bar selects the event, on a turn header the turn,
 * on a row label that slot (first turn that has it).
 */
public final class Timeline extends JPanel implements Scrollable {
    static final int ROW = 20, LEFT = 180, HEAD = 18, RULER = 18, TOP = HEAD + RULER;
    final Project p;
    FileDoc doc;
    int si = -1, selfSpm = -1;
    String selectedPtr;
    int pxPerFrame = 6;
    SkillSeq seq;
    int playhead = -1;
    Consumer<WazNav.Ev> onSelect;
    /** (si, turn) — turn header clicked. */
    java.util.function.BiConsumer<Integer, Integer> onTurn;
    /** {si, turn, unitIdx} — row label clicked. */
    Consumer<int[]> onSlot;
    /** v5: double click on a bar that spawns / references a skill: {wazFileNo (-1 = own), wazSequenceNo}. */
    Consumer<int[]> onOpenRef;
    /** v5: right click: (event or null, turn or -1, mouse event). */
    ContextHandler onContext;
    public interface ContextHandler { void show(WazNav.Ev ev, int turn, MouseEvent e); }

    public Timeline(Project p) {
        this.p = p;
        setBackground(Color.WHITE);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { context(e); return; }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.getClickCount() == 2) { WazNav.Ev ev = at(e.getX(), e.getY()); int[] r = ev == null ? null : refOf(ev.node); if (r != null && onOpenRef != null) { onOpenRef.accept(r); return; } }
                    click(e.getX(), e.getY());
                }
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) context(e); }
        });
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                pxPerFrame = Math.max(2, Math.min(40, pxPerFrame - e.getWheelRotation()));
                revalidate(); repaint();
            } else getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
        });
        setToolTipText("");
    }

    void context(MouseEvent e) {
        if (seq == null || onContext == null) return;
        WazNav.Ev ev = at(e.getX(), e.getY());
        onContext.show(ev, ev != null ? ev.turn : turnAtHeader(e.getX(), e.getY()), e);
    }

    /** v5: the first skill reference (wazFileNo / wazSequenceNo) inside an event, or null. */
    public static int[] refOf(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null) return null;
        if (n.isObject() && n.has("wazFileNo") && n.has("wazSequenceNo")) return new int[]{n.get("wazFileNo").asInt(), n.get("wazSequenceNo").asInt()};
        if (n.isContainerNode()) for (com.fasterxml.jackson.databind.JsonNode c : n) { int[] r = refOf(c); if (r != null) return r; }
        return null;
    }

    /** Turn mode (one turn); turn &lt; 0 clears. Kept for v2 callers. */
    public void show(FileDoc doc, int si, int turn, int selfSpm, String selectedPtr) {
        set(doc, si, doc == null || si < 0 || turn < 0 ? null : SkillSeq.turn(doc.tree, si, turn), selfSpm, selectedPtr);
    }

    /** Skill mode: all turns of the skill concatenated (jumps ignored). */
    public void showSkill(FileDoc doc, int si, int selfSpm, String selectedPtr) {
        set(doc, si, doc == null || si < 0 ? null : SkillSeq.all(doc.tree, si), selfSpm, selectedPtr);
    }

    void set(FileDoc doc, int si, SkillSeq seq, int selfSpm, String selectedPtr) {
        boolean newLayout = seq == null || this.seq == null || doc != this.doc || si != this.si || seq.turnFilter != this.seq.turnFilter;
        this.doc = doc; this.si = si; this.seq = seq; this.selfSpm = selfSpm; this.selectedPtr = selectedPtr;
        // fit a new layout into the visible width when possible (4..12 px per frame); Ctrl+wheel still zooms freely
        if (newLayout && seq != null && getParent() instanceof JViewport) {
            int avail = ((JViewport) getParent()).getWidth() - LEFT - 24;
            if (avail > 0) pxPerFrame = Math.max(4, Math.min(48, avail / Math.max(1, seq.total + 2)));
        }
        playhead = -1;
        revalidate();
        repaint();
    }

    /** Rebuilds the layout from the (possibly edited) tree, keeping the mode. */
    public void refresh() {
        if (doc == null || seq == null) { repaint(); return; }
        if (si >= doc.tree.path("skillList").size()) { set(null, -1, null, -1, null); return; }
        seq = seq.isAll() ? SkillSeq.all(doc.tree, si) : SkillSeq.turn(doc.tree, si, seq.turnFilter);
        revalidate();
        repaint();
    }

    public boolean isSkillMode() { return seq != null && seq.isAll(); }
    public boolean showsSkill(FileDoc d, int s) { return seq != null && doc == d && si == s; }
    public SkillSeq seq() { return seq; }
    public int skill() { return si; }
    public String selectedPtr() { return selectedPtr; }

    /** Red playhead at global frame g of the shown layout (-1 hides it). */
    public void setPlayhead(int g) { if (g != playhead) { playhead = g; repaint(); } }

    int frames() { return seq == null ? 10 : Math.max(10, seq.total + 2); }

    @Override public Dimension getPreferredSize() {
        int rows = seq == null ? 1 : Math.max(1, seq.slots.size());
        return new Dimension(LEFT + frames() * pxPerFrame + 20, TOP + rows * ROW + 6);
    }

    int x(int g) { return LEFT + g * pxPerFrame; }

    Rectangle bar(SkillSeq.Placed e) {
        int r = seq.slots.indexOf(e.ev.slot);
        int x0 = x(e.gStart), x1 = x(e.gEnd + 1);
        return new Rectangle(x0, TOP + r * ROW + 2, Math.max(3, x1 - x0 - 1), ROW - 4);
    }

    /** The event bar under (x, y), or null. Later events win on overlap (they are drawn on top). */
    public WazNav.Ev at(int x, int y) {
        if (seq == null) return null;
        WazNav.Ev best = null;
        for (SkillSeq.Placed e : seq.events) if (bar(e).contains(x, y)) best = e.ev;
        return best;
    }

    /** Turn whose header contains x (y in the header), or -1. */
    public int turnAtHeader(int x, int y) {
        if (seq == null || y >= TOP || x < LEFT) return -1;
        int g = (x - LEFT) / Math.max(1, pxPerFrame);
        if (g >= seq.total) return -1;
        int i = seq.turnIndexAt(g);
        return i < 0 ? -1 : seq.turns[i];
    }

    /** Pixel centre of an event's bar (tests / programmatic clicks). */
    public Point centerOf(String ptr) {
        if (seq == null) return null;
        for (SkillSeq.Placed e : seq.events) if (e.ev.ptr.equals(ptr)) { Rectangle b = bar(e); return new Point(b.x + Math.min(b.width / 2, 4), b.y + b.height / 2); }
        return null;
    }

    /** One click: event bar → event, turn header → turn, row label → slot. */
    public void click(int x, int y) {
        if (seq == null || doc == null) return;
        WazNav.Ev hit = at(x, y);
        if (hit != null) { if (onSelect != null) onSelect.accept(hit); return; }
        int t = turnAtHeader(x, y);
        if (t >= 0) { if (onTurn != null) onTurn.accept(si, t); return; }
        if (x < LEFT && y >= TOP) {
            int r = (y - TOP) / ROW;
            if (r >= 0 && r < seq.slots.size()) {
                int[] tu = seq.firstUnit(seq.slots.get(r));
                if (tu != null && onSlot != null) onSlot.accept(new int[]{si, tu[0], tu[1]});
            }
        }
    }

    @Override public String getToolTipText(MouseEvent m) {
        WazNav.Ev e = at(m.getX(), m.getY());
        if (e != null) {
            bsdx.workshop.data.Fields.Slot sl = p.fields.slots.get(e.slot);
            String ux = sl == null || sl.ux.isEmpty() ? "" : "<br><font color='#1a5c99'>" + Form.esc(sl.ux) + "</font>";
            return "<html><body style='width:360px'>[" + e.slot + "] " + Form.esc(p.fields.slotName(e.slot)) + "　ターン " + e.turn + "　F " + e.start() + "-" + e.end() + "<br>"
                    + Form.esc(EventText.summary(p, e.node, selfSpm)) + ux + "<br><font color='gray'>クリックでこのイベントへ" + (refOf(e.node) != null ? "／ダブルクリックで参照先の技へ" : "") + "／右クリックでメニュー</font></body></html>";
        }
        if (seq != null && m.getX() < LEFT && m.getY() >= TOP) {      // v4: row label = slot -> its explanation
            int r = (m.getY() - TOP) / ROW;
            if (r >= 0 && r < seq.slots.size()) return Form.html(p.fields.slotTip(seq.slots.get(r)));
        }
        int t = turnAtHeader(m.getX(), m.getY());
        if (t >= 0) return "ターン " + t + " — クリックでこのターンへ";
        return null;
    }

    static Color colorOf(int typeId) {
        switch (typeId) {
            case 13: return new Color(120, 170, 230);
            case 16: return new Color(235, 90, 80);
            case 19: case 4: return new Color(240, 160, 60);
            case 23: case 24: return new Color(120, 200, 120);
            case 34: return new Color(170, 130, 210);
            default: return new Color(190, 190, 190);
        }
    }

    boolean selectedInTurn(int t) {
        if (selectedPtr == null) return false;
        String pre = WazNav.skillPtr(si) + "/phasesInfo/" + t;
        return selectedPtr.equals(pre) || selectedPtr.startsWith(pre + "/");
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (doc == null || seq == null) {
            g.setColor(Color.GRAY);
            g.drawString("技を選ぶと全ターンを連結したタイムライン、ターンを選ぶとそのターンを表示します（Ctrl+ホイールで拡大縮小）", 8, 16);
            return;
        }
        int h = getHeight(), w = getWidth();
        Font base = g.getFont();
        // left header cell
        g.setColor(new Color(236, 238, 246));
        g.fillRect(0, 0, LEFT, TOP);
        g.setColor(Color.DARK_GRAY);
        g.drawString(seq.isAll() ? "技 " + si + "：全ターン（ジャンプ無視）" : "技 " + si + "：ターン " + seq.turnFilter, 4, 14);
        g.setFont(base.deriveFont(10f));
        g.drawString("全 " + seq.total + "F　スロット ＼ フレーム", 4, HEAD + 12);
        g.setFont(base);
        // turn headers + rulers
        for (int i = 0; i < seq.turns.length; i++) {
            int t = seq.turns[i], x0 = x(seq.turnStart[i]), x1 = x(seq.turnStart[i] + seq.turnLen[i]);
            boolean cur = selectedInTurn(t);
            g.setColor(cur ? new Color(255, 236, 170) : i % 2 == 0 ? new Color(232, 236, 248) : new Color(222, 228, 244));
            g.fillRect(x0, 0, x1 - x0, HEAD);
            g.setColor(i % 2 == 0 ? new Color(250, 250, 253) : new Color(244, 246, 252));
            g.fillRect(x0, HEAD, x1 - x0, h - HEAD);
            // per-turn frame grid, numbers from 0
            // v5: a faint line and ruler tick for every frame, stronger every 5 / 10 frames
            for (int f = 0; f <= seq.turnLen[i]; f++) {
                int x = x(seq.turnStart[i] + f);
                boolean ten = f % 10 == 0, five = f % 5 == 0;
                if (ten || five || pxPerFrame >= 3) {
                    g.setColor(ten ? new Color(196, 200, 214) : five ? new Color(222, 225, 234) : new Color(238, 240, 245));
                    g.drawLine(x, TOP, x, h);
                }
                g.setColor(ten ? new Color(90, 95, 110) : five ? new Color(140, 145, 160) : new Color(185, 190, 202));
                int tick = ten ? 9 : five ? 6 : 3;
                g.drawLine(x, TOP - tick, x, TOP - 1);
                boolean label = ten || (five && pxPerFrame >= 6) || pxPerFrame >= 18;
                if (label && f < seq.turnLen[i] && (x1 - x) > 12) {
                    g.setColor(ten ? Color.DARK_GRAY : Color.GRAY);
                    g.setFont(base.deriveFont(10f));
                    g.drawString(String.valueOf(f), x + 2, HEAD + 10);
                    g.setFont(base);
                }
            }
            g.setColor(Color.BLACK);
            Shape clip = g.getClip();
            g.clipRect(x0, 0, x1 - x0, HEAD);
            FontMetrics fm = g.getFontMetrics();
            String lab = "ターン " + t + "（" + seq.turnLen[i] + "F）";
            if (fm.stringWidth(lab) > x1 - x0 - 6) lab = "ターン " + t;
            if (fm.stringWidth(lab) > x1 - x0 - 6) lab = "T" + t;
            g.drawString(lab, x0 + 3, 14);
            g.setClip(clip);
            g.setColor(new Color(70, 80, 120));
            g.drawLine(x0, 0, x0, h);
            g.drawLine(x0 + 1, 0, x0 + 1, HEAD + RULER);
        }
        int xe = x(seq.total);
        g.setColor(new Color(70, 80, 120));
        g.drawLine(xe, 0, xe, h);
        g.setColor(new Color(160, 165, 185));
        g.drawLine(0, HEAD, w, HEAD);
        g.drawLine(0, TOP, w, TOP);
        // rows
        for (int r = 0; r < seq.slots.size(); r++) {
            int slot = seq.slots.get(r);
            g.setColor(Color.BLACK);
            Shape clip = g.getClip();
            g.clipRect(0, TOP + r * ROW, LEFT - 2, ROW);
            g.drawString("[" + slot + "] " + p.fields.slotName(slot) + (bsdx.workshop.data.ExeTerms.isGameTerm(p.fields.slotName(slot)) ? "" : Form.COINED_MARK), 6, TOP + r * ROW + 14);
            g.setClip(clip);
            g.setColor(new Color(230, 230, 230));
            g.drawLine(0, TOP + (r + 1) * ROW, w, TOP + (r + 1) * ROW);
        }
        g.setColor(new Color(160, 165, 185));
        g.drawLine(LEFT - 1, 0, LEFT - 1, h);
        // bars
        g.setFont(base.deriveFont(10f));
        java.util.Set<String> changed = doc.changes().keySet();
        for (SkillSeq.Placed e : seq.events) {
            Rectangle b = bar(e);
            g.setColor(colorOf(e.ev.typeId()));
            g.fillRect(b.x, b.y, b.width, b.height);
            boolean sel = e.ev.ptr.equals(selectedPtr);
            boolean ch = false;
            for (String k : changed) if (k.startsWith(e.ev.ptr + "/")) { ch = true; break; }
            g.setColor(sel ? Color.BLACK : ch ? new Color(200, 100, 0) : new Color(90, 90, 90));
            g.drawRect(b.x, b.y, b.width, b.height);
            if (sel) { g.drawRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2); g.drawRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4); }
            if (b.width > 30) {
                Shape clip = g.getClip();
                g.clipRect(b.x, b.y, b.width, b.height);
                g.setColor(Color.BLACK);
                g.drawString(EventText.summary(p, e.ev.node, selfSpm), b.x + 3, b.y + 12);
                g.setClip(clip);
            }
        }
        g.setFont(base);
        if (playhead >= 0 && playhead <= seq.total) {
            int x = x(playhead) + pxPerFrame / 2;
            g.setColor(new Color(220, 0, 0));
            g.drawLine(x, HEAD, x, h);
            g.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{HEAD, HEAD, HEAD + 6}, 3);
        }
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(600, 200); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 160; }
    @Override public boolean getScrollableTracksViewportWidth() { return false; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
