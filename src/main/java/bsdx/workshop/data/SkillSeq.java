package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * v3: a skill laid out on ONE frame axis. Turns are appended left to right in their stored order
 * (ターン0 | ターン1 | …); jump / branch instructions (変更処理, ターンジャンプ) are deliberately ignored.
 * Each turn keeps its own local frame numbers (ruler starts at 0 in every turn); the global frame of an event is
 * turnStart[turn] + startFrame. Used by the skill-level timeline and the concatenated sprite preview.
 */
public final class SkillSeq {
    /** Slot of the main motion event (CEventSprite: spm file / anim / pattern per frame interval). */
    public static final int SPRITE_SLOT = 0;

    public static final class Placed {
        public final WazNav.Ev ev;
        public final int gStart, gEnd;
        Placed(WazNav.Ev ev, int off) { this.ev = ev; this.gStart = off + ev.start(); this.gEnd = off + ev.end(); }
    }

    /** One interval of the main sprite track: shows (spm, anim, pattern) from gStart to gEnd (inclusive). */
    public static final class SpriteSeg {
        public final int gStart, gEnd, turn, localStart, spmSel, anim, pat;
        public final String ptr;
        SpriteSeg(Placed pl) {
            gStart = pl.gStart; gEnd = pl.gEnd; turn = pl.ev.turn; localStart = pl.ev.start(); ptr = pl.ev.ptr;
            spmSel = pl.ev.node.path("spmFileSequence").asInt(-1);
            anim = pl.ev.node.path("actionGroupNumber").asInt();
            pat = pl.ev.node.path("actionNumber").asInt();
        }
        @Override public String toString() { return "T" + turn + " g" + gStart + "-" + gEnd + " spm" + spmSel + " anim" + anim + "#" + pat; }
    }

    public final int si;
    /** -1 = all turns concatenated, otherwise the only turn shown. */
    public final int turnFilter;
    /** Turns in display order (all turns, or just turnFilter). */
    public final int[] turns, turnStart, turnLen;
    public final int total;
    public final List<Placed> events = new ArrayList<>();
    /** Slot numbers present in the shown turns, ascending (= timeline rows). */
    public final List<Integer> slots = new ArrayList<>();
    public final List<SpriteSeg> sprite = new ArrayList<>();

    private SkillSeq(JsonNode waz, int si, int turnFilter) {
        this.si = si;
        this.turnFilter = turnFilter;
        JsonNode s = WazNav.skill(waz, si);
        int nt = s == null ? 0 : s.path("phasesInfo").size();
        List<Integer> ts = new ArrayList<>();
        for (int t = 0; t < nt; t++) if (turnFilter < 0 || t == turnFilter) ts.add(t);
        turns = new int[ts.size()]; turnStart = new int[ts.size()]; turnLen = new int[ts.size()];
        int off = 0;
        TreeSet<Integer> sl = new TreeSet<>();
        for (int i = 0; i < ts.size(); i++) {
            int t = ts.get(i);
            turns[i] = t;
            turnStart[i] = off;
            List<WazNav.Ev> evs = WazNav.events(waz, si, t);
            int len = 0;
            for (WazNav.Ev e : evs) { len = Math.max(len, e.end() + 1); events.add(new Placed(e, off)); sl.add(e.slot); }
            turnLen[i] = displayLength(len);
            off += turnLen[i];
        }
        total = off;
        slots.addAll(sl);
        for (Placed p : events) if (p.ev.slot == SPRITE_SLOT && p.ev.typeId() == 13) sprite.add(new SpriteSeg(p));
        sprite.sort(Comparator.comparingInt((SpriteSeg g) -> g.gStart).thenComparingInt(g -> g.gEnd));
    }

    /** An empty turn still occupies one frame so it stays visible / clickable. */
    public static int displayLength(int len) { return Math.max(1, len); }

    /** All turns of skill si, appended in order (jumps ignored). */
    public static SkillSeq all(JsonNode waz, int si) { return new SkillSeq(waz, si, -1); }

    /** Only turn t of skill si (frames start at 0). */
    public static SkillSeq turn(JsonNode waz, int si, int t) { return new SkillSeq(waz, si, t); }

    public boolean isAll() { return turnFilter < 0; }

    /** Index into turns[] of the turn that contains global frame g (clamped), or -1 if there are no turns. */
    public int turnIndexAt(int g) {
        if (turns.length == 0) return -1;
        for (int i = turns.length - 1; i >= 0; i--) if (g >= turnStart[i]) return i;
        return 0;
    }

    public int indexOfTurn(int t) { for (int i = 0; i < turns.length; i++) if (turns[i] == t) return i; return -1; }

    /** Global frame of (turn, local frame), or -1 when the turn is not shown. */
    public int global(int t, int local) { int i = indexOfTurn(t); return i < 0 ? -1 : turnStart[i] + local; }

    /**
     * Main sprite shown at global frame g: the active sprite event that started last; between events the last
     * started one is held (the engine keeps the last set pattern — 推定). null before the first sprite event.
     */
    public SpriteSeg spriteAt(int g) {
        SpriteSeg active = null, held = null;
        for (SpriteSeg s : sprite) {
            if (s.gStart > g) break;
            held = s;
            if (g <= s.gEnd) active = s;
        }
        return active != null ? active : held;
    }

    /** Placed events of one slot in display order. */
    public List<Placed> row(int slot) {
        List<Placed> r = new ArrayList<>();
        for (Placed p : events) if (p.ev.slot == slot) r.add(p);
        return r;
    }

    /** First (turn, unitIdx) that has the slot, for "click the row label" navigation; null if none. */
    public int[] firstUnit(int slot) {
        for (Placed p : events) if (p.ev.slot == slot) return new int[]{p.ev.turn, p.ev.unitIdx};
        return null;
    }
}
