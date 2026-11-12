package bsdx.workshop.ui;

import bsdx.workshop.data.Project;
import bsdx.workshop.data.SkillSeq;
import com.giga.nexas.dto.bsdx.spm.Spm;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Small animation preview of one spm animation (mech / battle sprites only). Pages are rendered from the chip
 * list + png sheets (drawOption 256 = additive, 128 = translucent). Hit rects (ground plane) can be overlaid:
 * red = attack bits 0-10, green = body bit 13, blue = others.
 */
public final class SpmPreview extends JPanel {
    /** v3 defaults requested by the user: hit rects ON, direction 2. */
    public static final int DEFAULT_DIR = 2;
    public static final boolean DEFAULT_HIT = true;
    private final Project p;
    private Spm spm;
    private String spmName;
    private int anim = -1, pat = -1, dir = DEFAULT_DIR, frame = 0, tick = 0;
    private boolean playing = true, showHit = DEFAULT_HIT;
    private final Timer timer;
    private String message = "";
    // v3 sequence mode (skill: all turns appended, jumps ignored / turn: one turn)
    private SkillSeq seq;
    private int selfSpm = -1, gFrame = 0;
    private java.util.function.IntConsumer onFrame;

    public SpmPreview(Project p) {
        this.p = p;
        setPreferredSize(new Dimension(260, 240));
        setBackground(new Color(52, 54, 62));
        timer = new Timer(1000 / 60, e -> step());
    }

    public void setDir(int d) { dir = d; repaint(); }
    public void setShowHit(boolean b) { showHit = b; repaint(); }
    public int dir() { return dir; }
    public boolean showHit() { return showHit; }
    public void setPlaying(boolean b) { playing = b; syncTimer(); repaint(); }
    public boolean isPlaying() { return playing; }

    /** The timer runs only while the panel is on screen and something can play. */
    private void syncTimer() {
        boolean can = playing && isDisplayable() && message.isEmpty() && (seq != null ? seq.total > 0 : spm != null && anim >= 0);
        if (can) timer.start(); else timer.stop();
    }

    public void show(String spmFile, int anim, int pat) {
        this.seq = null;
        this.spmName = spmFile;
        this.spm = spmFile == null ? null : p.spm(spmFile);
        this.anim = anim; this.pat = pat; this.frame = Math.max(0, pat); this.tick = 0;
        message = spm == null ? (spmFile == null ? "スプライト未登録" : spmFile + " が見つかりません\n(System.pac を含むゲームフォルダを指定してください)") : "";
        if (spm != null && (anim < 0 || anim >= spm.getAnimData().size())) message = "アニメ番号 " + anim + " は範囲外";
        syncTimer();
        repaint();
    }

    /**
     * v3: plays the main sprite track ([0] スプライト) of a skill (all turns appended, jumps ignored) or of one turn.
     * Each sprite event shows its (spm, anim, pattern) for its frame interval; between events the last one is held.
     * selfSpm resolves spmFileSequence -1 (自機). onFrame receives the global frame (timeline playhead).
     */
    public void showSequence(SkillSeq seq, int selfSpm, java.util.function.IntConsumer onFrame) {
        this.seq = seq; this.selfSpm = selfSpm; this.onFrame = onFrame; this.gFrame = 0; this.tick = 0;
        message = "";
        if (seq == null || seq.total <= 0) message = "ターンがありません";
        else if (seq.sprite.isEmpty()) message = "[0] スプライト のイベントがありません（表示するモーションなし）";
        syncTimer();
        repaint();
    }

    public SkillSeq sequence() { return seq; }
    public int globalFrame() { return gFrame; }
    public void setGlobalFrame(int g) {
        if (seq == null) return;
        gFrame = Math.max(0, Math.min(Math.max(0, seq.total - 1), g));
        if (onFrame != null) onFrame.accept(gFrame);
        repaint();
    }

    /** spm file of a sequence segment ("-1" = the mech's own spm). */
    public String segFile(SkillSeq.SpriteSeg s) { return s == null ? null : p.spmFile(s.spmSel == -1 ? selfSpm : s.spmSel); }

    @Override public void addNotify() { super.addNotify(); syncTimer(); }
    @Override public void removeNotify() { super.removeNotify(); timer.stop(); }

    private void step() {
        if (seq != null) {
            if (seq.total <= 0) return;
            setGlobalFrame((gFrame + 1) % seq.total);
            return;
        }
        if (spm == null || anim < 0 || anim >= spm.getAnimData().size()) return;
        List<Spm.SPMPatData> pats = spm.getAnimData().get(anim).getPatData();
        if (pats == null || pats.isEmpty()) return;
        int w = Math.max(1, pats.get(Math.min(frame, pats.size() - 1)).getWaitFrame());
        if (++tick >= w) { tick = 0; frame = (frame + 1) % pats.size(); repaint(); }
    }

    int pageOf(int f) { return pageOf(spm, anim, f, dir); }

    /** Page index of pattern f of an animation in direction d (falls back to the base page), or -1. */
    public static int pageOf(Spm spm, int anim, int f, int d) {
        if (spm == null || anim < 0 || anim >= spm.getAnimData().size()) return -1;
        List<Spm.SPMPatData> pats = spm.getAnimData().get(anim).getPatData();
        if (pats == null || pats.isEmpty()) return -1;
        Spm.SPMPatData pd = pats.get(Math.max(0, Math.min(f, pats.size() - 1)));
        if (pd.getPageNo() == null || pd.getPageNo().isEmpty()) return -1;
        int base = pd.getPageNo().get(0);
        int pg = base + d;
        if (pg >= spm.getPageData().size() || spm.getPageData().get(pg).getChipData() == null || spm.getPageData().get(pg).getChipData().isEmpty()) pg = base;
        return pg < spm.getPageData().size() ? pg : -1;
    }

    /** Renders one page into an ARGB canvas with the origin at (ox, oy). */
    public static void renderPage(Project p, Spm spm, int pageIdx, int[] canvas, int cw, int ch, int ox, int oy) {
        Spm.SPMPageData page = spm.getPageData().get(pageIdx);
        if (page.getChipData() == null) return;
        for (Spm.SPMChipData c : page.getChipData()) {
            if (c.getImageNo() == null || c.getImageNo() < 0 || c.getImageNo() >= spm.getImageData().size()) continue;
            BufferedImage img = p.png(spm.getImageData().get(c.getImageNo()).getImageName());
            if (img == null) continue;
            Spm.SPMRect s = c.getSrcRect(), d = c.getDstRect();
            int dw = d.getRight() - d.getLeft(), dh = d.getBottom() - d.getTop();
            int sw = s.getRight() - s.getLeft(), sh = s.getBottom() - s.getTop();
            if (dw == 0 || dh == 0 || sw == 0 || sh == 0) continue;
            long op = c.getDrawOption() == null ? 0 : c.getDrawOption() & 0xFFFF;
            long val = c.getDrawOptionValue() == null ? 256 : c.getDrawOptionValue();
            int adw = Math.abs(dw), adh = Math.abs(dh);
            for (int y = 0; y < adh; y++) {
                int cy = oy + Math.min(d.getTop(), d.getBottom()) + y;
                if (cy < 0 || cy >= ch) continue;
                int sy = s.getTop() + (dh > 0 ? y : adh - 1 - y) * sh / adh;
                if (sy < 0 || sy >= img.getHeight()) continue;
                for (int x = 0; x < adw; x++) {
                    int cx = ox + Math.min(d.getLeft(), d.getRight()) + x;
                    if (cx < 0 || cx >= cw) continue;
                    int sx = s.getLeft() + (dw > 0 ? x : adw - 1 - x) * sw / adw;
                    if (sx < 0 || sx >= img.getWidth()) continue;
                    int src = img.getRGB(sx, sy);
                    int a = src >>> 24;
                    if (a == 0) continue;
                    int dst = canvas[cy * cw + cx];
                    int r = (src >> 16) & 255, g = (src >> 8) & 255, b = src & 255;
                    int dr = (dst >> 16) & 255, dg = (dst >> 8) & 255, db = dst & 255;
                    if (op == 256) {
                        int k = (int) Math.min(256, Math.max(0, val)) * a / 255;
                        dr = Math.min(255, dr + r * k / 256); dg = Math.min(255, dg + g * k / 256); db = Math.min(255, db + b * k / 256);
                    } else {
                        if (op == 128) a = (int) (a * Math.min(256, Math.max(0, val)) / 256);
                        dr = (r * a + dr * (255 - a)) / 255; dg = (g * a + dg * (255 - a)) / 255; db = (b * a + db * (255 - a)) / 255;
                    }
                    canvas[cy * cw + cx] = 0xFF000000 | dr << 16 | dg << 8 | db;
                }
            }
        }
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth(), h = getHeight();
        g.setColor(Color.LIGHT_GRAY);
        if (!message.isEmpty() || (seq == null && spm == null)) {
            int y = 20;
            for (String line : message.split("\n")) { g.drawString(line, 8, y); y += 16; }
            return;
        }
        int ox = w / 2, oy = h * 3 / 4;
        if (seq != null) {
            SkillSeq.SpriteSeg s = seq.spriteAt(gFrame);
            String fn = segFile(s);
            Spm sp = fn == null ? null : p.spm(fn);
            int ti = seq.turnIndexAt(gFrame);
            int turn = ti < 0 ? -1 : seq.turns[ti], local = ti < 0 ? gFrame : gFrame - seq.turnStart[ti];
            int pg = -1;
            if (sp != null && s != null) { pg = pageOf(sp, s.anim, s.pat, dir); drawPage(g, sp, pg, w, h, ox, oy); }
            g.setColor(Color.YELLOW);
            g.drawLine(ox - 4, oy, ox + 4, oy);
            g.setColor(Color.WHITE);
            g.drawString(String.format("%s  ターン %d  F %d / 全体 %d / %dF", seq.isAll() ? "全ターン連結" : "ターン単体", turn, local, gFrame, seq.total), 4, 14);
            if (s == null) { g.setColor(Color.LIGHT_GRAY); g.drawString("（この時点ではスプライト指定なし）", 4, 30); }
            else if (sp == null) { g.setColor(Color.ORANGE); g.drawString((fn == null ? "spm 未登録 (" + s.spmSel + ")" : fn + " が見つかりません"), 4, 30); }
            else {
                Spm.SPMAnimData a = s.anim < sp.getAnimData().size() ? sp.getAnimData().get(s.anim) : null;
                g.drawString(String.format("%s  anim %d「%s」 pat %d  page %d  dir %d%s", fn, s.anim, a == null || a.getAnimName() == null ? "" : a.getAnimName().trim(), s.pat, pg, dir,
                        gFrame > s.gEnd ? "（保持）" : ""), 4, 30);
            }
            return;
        }
        int pg = pageOf(frame);
        drawPage(g, spm, pg, w, h, ox, oy);
        g.setColor(Color.YELLOW);
        g.drawLine(ox - 4, oy, ox + 4, oy);
        g.setColor(Color.WHITE);
        Spm.SPMAnimData a = spm.getAnimData().get(anim);
        g.drawString(String.format("%s  anim %d「%s」 pat %d/%d  page %d  dir %d", spmName, anim,
                a.getAnimName() == null ? "" : a.getAnimName().trim(), frame, a.getPatData() == null ? 0 : a.getPatData().size(), pg, dir), 4, 14);
        if (pat >= 0 && frame == pat) { g.setColor(Color.ORANGE); g.drawString("← 選択中のパターン", 4, 30); }
    }

    private void drawPage(Graphics2D g, Spm sp, int pg, int w, int h, int ox, int oy) {
        if (pg < 0 || w <= 0 || h <= 0) return;
        int[] canvas = new int[w * h];
        java.util.Arrays.fill(canvas, getBackground().getRGB());
        renderPage(p, sp, pg, canvas, w, h, ox, oy);
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, canvas, 0, w);
        g.drawImage(bi, 0, 0, null);
        if (!showHit) return;
        Spm.SPMPageData page = sp.getPageData().get(pg);
        long flag = page.getHitFlag() == null ? 0 : page.getHitFlag();
        int k = 0;
        for (int bit = 0; bit < 32 && page.getHitRects() != null && k < page.getHitRects().size(); bit++) {
            if ((flag >> bit & 1) == 0) continue;
            Spm.SPMRect r = page.getHitRects().get(k++).getHitRect();
            g.setColor(bit <= 10 ? Color.RED : bit == 13 ? Color.GREEN : new Color(80, 160, 255));
            g.drawRect(ox + r.getLeft(), oy + r.getTop() / 2, r.getRight() - r.getLeft(), (r.getBottom() - r.getTop()) / 2);
        }
    }
}
