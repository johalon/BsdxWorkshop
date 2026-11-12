package bsdx.workshop.ui;

import java.awt.*;

/** Component tree search helper. */
final class UiFind {
    private UiFind() {}

    @SuppressWarnings("unchecked")
    static <T> T first(Container c, Class<T> cls) {
        for (Component k : c.getComponents()) {
            if (cls.isInstance(k) && k.isShowing()) return (T) k;
            if (k instanceof Container) { T r = first((Container) k, cls); if (r != null) return r; }
        }
        for (Component k : c.getComponents()) {
            if (cls.isInstance(k)) return (T) k;
            if (k instanceof Container) { T r = first((Container) k, cls); if (r != null) return r; }
        }
        return null;
    }
}
