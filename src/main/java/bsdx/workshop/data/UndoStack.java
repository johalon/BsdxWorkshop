package bsdx.workshop.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** Undo / redo of value edits on FileDoc JSON trees. Every edit is a list of (doc, pointer, old, new) changes. */
public final class UndoStack {
    public static final class Change {
        public final FileDoc doc; public final String ptr; public final JsonNode oldV, newV;
        /** Shallow: containers are copied one level only (children shared). Used for structural edits of big lists
         *  (skills / turns / events / weapons) - correct because undo is strictly LIFO, see notes/13. */
        public final boolean shallow;
        /** This change created the doc's "original value" entry (removed again on undo so pointers never go stale). */
        boolean createdOriginal;
        public Change(FileDoc doc, String ptr, JsonNode oldV, JsonNode newV) { this(doc, ptr, oldV, newV, false); }
        public Change(FileDoc doc, String ptr, JsonNode oldV, JsonNode newV, boolean shallow) {
            this.doc = doc; this.ptr = ptr; this.shallow = shallow; this.oldV = UndoStack.copy(oldV, shallow); this.newV = UndoStack.copy(newV, shallow);
        }
        JsonNode copy(JsonNode n) { return UndoStack.copy(n, shallow); }
    }

    public static final class Edit {
        public final String label; public final List<Change> changes;
        public boolean structural;
        Edit(String label, List<Change> changes) { this.label = label; this.changes = changes; }
    }

    private final Deque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private List<Change> pending;
    private String pendingLabel;
    private boolean pendingStructural;
    /** The edit applied / undone / redone most recently (for listeners). */
    public Edit lastEdit;
    /** Direction of lastEdit: +1 applied/redone, -1 undone. */
    public int lastDir;

    static JsonNode copy(JsonNode n, boolean shallow) {
        if (n == null) return null;
        if (!shallow || !n.isContainerNode()) return n.deepCopy();
        if (n.isArray()) { com.fasterxml.jackson.databind.node.ArrayNode a = Json.F.arrayNode(); n.forEach(a::add); return a; }
        com.fasterxml.jackson.databind.node.ObjectNode o = Json.F.objectNode();
        n.fields().forEachRemaining(e -> o.set(e.getKey(), e.getValue()));
        return o;
    }

    public void addListener(Runnable r) { listeners.add(r); }
    private void fire() { for (Runnable r : listeners) r.run(); }

    /** Sets one value as a single undoable edit (no-op if equal). */
    public boolean set(FileDoc doc, String ptr, JsonNode value, String label) { return set(doc, ptr, value, label, false); }

    /** Replaces a container with shallow copies recorded (structural edit of a big list). */
    public boolean setShallow(FileDoc doc, String ptr, JsonNode value, String label) { return set(doc, ptr, value, label, true); }

    private boolean set(FileDoc doc, String ptr, JsonNode value, String label, boolean shallow) {
        JsonNode old = Json.at(doc.tree, ptr);
        if (Objects.equals(old, value)) return false;
        Change c = new Change(doc, ptr, old, value, shallow);
        if (pending != null) { apply(c, true); pending.add(c); return true; }
        apply(c, true);
        Edit e = new Edit(label, new ArrayList<>(List.of(c)));
        e.structural = shallow;
        undo.push(e);
        redo.clear();
        lastEdit = e; lastDir = 1;
        fire();
        return true;
    }

    /** Groups several {@link #set} calls into one undo step. */
    public void begin(String label) { begin(label, false); }
    public void begin(String label, boolean structural) { pending = new ArrayList<>(); pendingLabel = label; pendingStructural = structural; }
    public boolean inGroup() { return pending != null; }

    public void end() {
        List<Change> p = pending; pending = null;
        if (p != null && !p.isEmpty()) {
            Edit e = new Edit(pendingLabel, p);
            e.structural = pendingStructural;
            undo.push(e); redo.clear();
            lastEdit = e; lastDir = 1;
            fire();
        }
    }

    /** Ends a group by reverting everything applied in it (used when a structural operation fails half way). */
    public void abort() {
        List<Change> p = pending; pending = null;
        if (p != null) for (int i = p.size() - 1; i >= 0; i--) apply(p.get(i), false);
    }

    private static void apply(Change c, boolean forward) {
        JsonNode v = forward ? c.newV : c.oldV;
        if (forward) {
            c.createdOriginal = !c.doc.originals.containsKey(c.ptr);
            if (c.createdOriginal) c.doc.originals.put(c.ptr, c.oldV == null ? null : c.copy(c.oldV));
        } else if (c.createdOriginal) {
            c.doc.originals.remove(c.ptr);
            c.createdOriginal = false;
        }
        Json.set(c.doc.tree, c.ptr, v == null ? null : c.copy(v));
        c.doc.editDepth += forward ? 1 : -1;
        c.doc.version++;
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public String undoLabel() { return undo.isEmpty() ? "" : undo.peek().label; }
    public String redoLabel() { return redo.isEmpty() ? "" : redo.peek().label; }

    public void undo() {
        if (undo.isEmpty()) return;
        Edit e = undo.pop();
        for (int i = e.changes.size() - 1; i >= 0; i--) apply(e.changes.get(i), false);
        redo.push(e);
        lastEdit = e; lastDir = -1;
        fire();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Edit e = redo.pop();
        for (Change c : e.changes) apply(c, true);
        undo.push(e);
        lastEdit = e; lastDir = 1;
        fire();
    }

    public List<Edit> history() { List<Edit> l = new ArrayList<>(undo); Collections.reverse(l); return l; }
    public void clear() { undo.clear(); redo.clear(); fire(); }
}
