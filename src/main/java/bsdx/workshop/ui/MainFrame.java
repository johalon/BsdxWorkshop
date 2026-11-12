package bsdx.workshop.ui;

import bsdx.workshop.data.*;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/** BsdxWorkshop main window. */
public final class MainFrame extends JFrame implements Form.Nav {
    public static final String VERSION = "5.0 (2026-11-12)";
    final Settings settings;
    final Codec codec;
    final Fields fields;
    Project p;

    // left
    final MechModel mechModel = new MechModel();
    final JTable mechTable = new JTable(mechModel);
    final JTextField mechFilter = new JTextField();
    final WeaponModel weaponModel = new WeaponModel();
    final JTable weaponTable = new JTable(weaponModel);
    final SharedModel sharedModel = new SharedModel();
    final JTable sharedTable = new JTable(sharedModel);
    // center / right
    SkillTree tree;
    Timeline timeline;
    final JPanel propHolder = new JPanel(new BorderLayout());
    final JLabel status = new JLabel(" ");
    final JLabel layersLabel = new JLabel(" ");
    final JTextArea layersArea = Form.note(" ");
    final JTextField searchField = new JTextField(14);
    final JButton undoBtn = new JButton("元に戻す"), redoBtn = new JButton("やり直し"), exportBtn = new JButton("書き出し");
    final JButton backBtn = new JButton("<"), fwdBtn = new JButton(">"), changesBtn = new JButton("変更 0");
    final JPanel center = new JPanel(new BorderLayout());
    final JScrollPane timelineScroll = new JScrollPane();
    // v5
    final JTabbedPane leftTabs = new JTabbedPane();
    final JTabbedPane centerTabs = new JTabbedPane();
    final JPanel outlineHolder = new JPanel(new BorderLayout());
    final JPanel overviewHolder = new JPanel(new BorderLayout());
    final JPanel crumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JLabel timelineTitle = new JLabel(" ");
    JPanel weaponPane;
    Outline outline;
    Overview overview;
    final History history = new History();
    Form.Ctx ctx;

    Project.Mech curMech;
    FileDoc curMek, curWaz;
    int curWazIdx = -1;
    boolean busy, recomputing, timelineClick;
    String curWeaponKey;
    /** What the property pane shows: 0 other, 1 mech page, 2 weapon page, 3 tree node page. */
    int propsKind;
    int mechTab;
    SearchIndex search;
    JMenu recentMenu;

    public MainFrame(Settings s, Codec codec, Fields fields) {
        super("BsdxWorkshop — BSDX 機体調整ツール");
        this.settings = s; this.codec = codec; this.fields = fields;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { quit(); } });
        setSize(1560, 960);
        setLocationByPlatform(true);
        try {
            String[] b = s.windowBounds.split(",");
            if (b.length == 4) {
                Rectangle r = new Rectangle(Integer.parseInt(b[0]), Integer.parseInt(b[1]), Integer.parseInt(b[2]), Integer.parseInt(b[3]));
                Rectangle screen = GraphicsEnvironment.isHeadless() ? r : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                if (r.width > 400 && r.height > 300 && screen.intersects(r)) setBounds(r);
            }
            if (s.maximized) setExtendedState(MAXIMIZED_BOTH);
        } catch (Exception ignored) {}
        buildMenu();
        buildLayout();
        setProps(Form.note("「ファイル → ゲームフォルダを開く」でゲームのフォルダ（BaldrSky.exe と *.pac があるフォルダ）を指定してください。\n"
                + "ゲームの pac は読み取り専用で開きます。書き出しは PROJECT フォルダ内（既定 mods\\gui_out）にだけ行います。"));
        refreshButtons();
    }

    // ================================================================= layout
    void buildMenu() {
        JMenuBar mb = new JMenuBar();
        JMenu f = new JMenu("ファイル");
        f.add(item("ゲームフォルダを開く…", null, this::chooseGameDir));
        f.add(item("オーバーレイ（MOD フォルダ）を開く…", null, () -> chooseOverlay(true)));
        f.add(item("オーバーレイ（Update3.pac など）を開く…", null, () -> chooseOverlay(false)));
        recentMenu = new JMenu("最近使ったもの");
        f.add(recentMenu);
        rebuildRecent();
        f.add(item("オーバーレイを外す", null, () -> { if (confirmDiscard()) { settings.overlay = ""; saveSettings(); openProject(); } }));
        f.add(item("再読み込み", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> { if (confirmDiscard()) openProject(); }));
        f.addSeparator();
        f.add(item("検証だけ行う…", null, () -> exportDialog(false)));
        f.add(item("書き出し（Update3.pac）…", KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK), () -> exportDialog(true)));
        f.addSeparator();
        f.add(item("設定…", null, this::settingsDialog));
        f.add(item("終了", null, this::quit));
        mb.add(f);
        JMenu ed = new JMenu("編集");
        ed.add(item("元に戻す", KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), this::undo));
        ed.add(item("やり直し", KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), this::redo));
        ed.add(item("変更の一覧…", null, this::showChanges));
        ed.add(item("戻る", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK), this::goBack));
        ed.add(item("進む", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK), this::goForward));
        ed.add(item("元との差分（ファイルごと）…", KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), this::showDiff));
        ed.addSeparator();
        ed.add(item("検索（技・武装・機体名）", KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK), () -> { searchField.requestFocusInWindow(); searchField.selectAll(); }));
        ed.add(item("検索（Ctrl+F でも可）", KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), () -> { searchField.requestFocusInWindow(); searchField.selectAll(); }));
        ed.add(item("選択中の SE / ボイスを試聴", KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), this::playSelected));
        ed.add(item("再生を止める", KeyStroke.getKeyStroke(KeyEvent.VK_F9, KeyEvent.SHIFT_DOWN_MASK), bsdx.workshop.audio.SoundPlayer::stop));
        mb.add(ed);
        JMenu st = new JMenu("構造");
        st.add(item("複製（直後に挿入）", KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK), () -> structAction("dup")));
        st.add(item("複製（末尾に追加）", KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK), () -> structAction("dupEnd")));
        st.add(item("新規追加（技 / 空のターン / イベント）…", KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), () -> structAction("add")));
        st.add(item("上へ（前へ）", KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK), () -> structAction("up")));
        st.add(item("下へ（後へ）", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK), () -> structAction("down")));
        st.add(item("削除…", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), () -> structAction("del")));
        st.addSeparator();
        st.add(item("AI 表を開く", KeyStroke.getKeyStroke(KeyEvent.VK_I, KeyEvent.CTRL_DOWN_MASK), this::openAiTab));
        st.add(new JMenuItem("<html><font color='gray'>対象: 選んでいる技・ターン・イベント、または左の一覧・武装表で選んだ武装</font></html>")).setEnabled(false);
        mb.add(st);
        JMenu h = new JMenu("ヘルプ");
        h.add(item("使い方（ヘルプ）", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), this::showHelp));
        h.add(item("バージョン", null, () -> JOptionPane.showMessageDialog(this, "BsdxWorkshop " + VERSION + "\nJava " + System.getProperty("java.version")
                + "\n辞書: " + fields.format + "\nデータ層: NeXAS_DX（プロジェクト修正版）")));
        mb.add(h);
        setJMenuBar(mb);
    }

    JMenuItem item(String name, KeyStroke ks, Runnable r) {
        JMenuItem i = new JMenuItem(name.replace("…", ""));
        if (ks != null) i.setAccelerator(ks);
        i.addActionListener(e -> r.run());
        return i;
    }

    void buildLayout() {
        // ---- top bar: back / forward, where you are, search, undo / redo, changes, export
        backBtn.setToolTipText("戻る（Alt+←、マウスの戻るボタン）");
        fwdBtn.setToolTipText("進む（Alt+→、マウスの進むボタン）");
        backBtn.addActionListener(e -> goBack());
        fwdBtn.addActionListener(e -> goForward());
        undoBtn.addActionListener(e -> undo());
        redoBtn.addActionListener(e -> redo());
        exportBtn.addActionListener(e -> exportDialog(true));
        exportBtn.setToolTipText("検証して Update3.pac を書き出す（Ctrl+E）");
        exportBtn.putClientProperty("FlatLaf.style", "background: #2f6fde; foreground: #ffffff; font: bold; borderColor: #2f6fde; hoverBackground: #2a63c7");
        changesBtn.setToolTipText("変更の一覧（どのファイルの何を変えたか・操作履歴）");
        changesBtn.addActionListener(e -> showChanges());
        searchField.setColumns(22);
        searchField.putClientProperty("JTextField.placeholderText", "技・武装・機体を検索（Ctrl+K）");
        searchField.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        searchField.setToolTipText("技名・武装名・機体名で検索（Enter）");
        searchField.addActionListener(e -> doSearch(searchField.getText()));
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(222, 224, 230)), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        nav.add(backBtn); nav.add(fwdBtn);
        JPanel west = new JPanel(new BorderLayout(8, 0));
        west.add(nav, BorderLayout.WEST);
        west.add(crumbs, BorderLayout.CENTER);
        top.add(west, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        east.add(searchField); east.add(undoBtn); east.add(redoBtn); east.add(changesBtn); east.add(exportBtn);
        top.add(east, BorderLayout.EAST);
        // ---- left: mech list / shared waz list, and the outline of the selection
        mechTable.setAutoCreateRowSorter(false);
        TableRowSorter<MechModel> sorter = new TableRowSorter<>(mechModel);
        mechTable.setRowSorter(sorter);
        mechTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mechFilter.putClientProperty("JTextField.placeholderText", "機体名・パイロット名で絞り込み");
        mechFilter.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        mechFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void u() { sorter.setRowFilter(mechFilter.getText().isBlank() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(mechFilter.getText().trim()))); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { u(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { u(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { u(); }
        });
        mechTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || mechTable.getSelectedRow() < 0 || recomputing) return;
            Project.Mech m = mechModel.rows.get(mechTable.convertRowIndexToModel(mechTable.getSelectedRow()));
            if (curMech == m && curWaz != null && curWazIdx == m.wazIdx) { showMechPage(); return; }
            selectMech(m, null);
        });
        mechTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        mechTable.setShowHorizontalLines(false);
        sharedTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JPanel mechPane = new JPanel(new BorderLayout(0, 4));
        mechPane.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        mechPane.add(mechFilter, BorderLayout.NORTH);
        mechPane.add(new JScrollPane(mechTable), BorderLayout.CENTER);
        sharedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sharedTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || sharedTable.getSelectedRow() < 0 || recomputing) return;
            int idx = sharedModel.rows.get(sharedTable.getSelectedRow());
            openWaz(idx, -1, null);
        });
        leftTabs.addTab("機体", mechPane);
        leftTabs.addTab("共通の弾・エフェクト", new JScrollPane(sharedTable));
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, leftTabs, outlineHolder);
        left.setResizeWeight(0.35);
        left.setDividerLocation(300);
        // ---- weapon table (shown in the overview on the mech page)
        weaponTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        weaponTable.getSelectionModel().addListSelectionListener(e -> {
            if (recomputing || e.getValueIsAdjusting() || weaponTable.getSelectedRow() < 0) return;
            selectWeapon(weaponModel.keys.get(weaponTable.getSelectedRow()));
        });
        weaponTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        weaponPane = new JPanel(new BorderLayout());
        weaponPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        JPanel wh = new JPanel(new BorderLayout());
        JLabel wl = new JLabel("武装（武装 → 技 → 攻撃）");
        wl.setFont(wl.getFont().deriveFont(Font.BOLD));
        wh.add(wl, BorderLayout.WEST);
        JPanel wb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        wb.add(AiEditor.btn("複製", () -> weaponAction("dupEnd")));
        wb.add(AiEditor.btn("削除", () -> weaponAction("del")));
        wb.add(AiEditor.btn("上へ", () -> weaponAction("up")));
        wb.add(AiEditor.btn("下へ", () -> weaponAction("down")));
        wh.add(wb, BorderLayout.EAST);
        wh.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        weaponPane.add(wh, BorderLayout.NORTH);
        weaponPane.add(new JScrollPane(weaponTable), BorderLayout.CENTER);
        // ---- centre (overview / detail tree) + right (editor)
        centerTabs.addTab("概要", overviewHolder);
        centerTabs.addTab("ツリー（ターン・スロット・イベント）", center);
        center.add(new JLabel(" ", SwingConstants.CENTER), BorderLayout.CENTER);
        propHolder.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(222, 224, 230)));
        JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerTabs, propHolder);
        upper.setResizeWeight(0.55);
        upper.setDividerLocation(760);
        // ---- timeline: full width under centre + right
        JPanel tl = new JPanel(new BorderLayout());
        JPanel th = new JPanel(new BorderLayout(10, 0));
        th.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        timelineTitle.setFont(timelineTitle.getFont().deriveFont(Font.BOLD));
        th.add(timelineTitle, BorderLayout.WEST);
        JLabel hint = new JLabel("クリック: 編集　ダブルクリック: 弾・エフェクトの生成先へ　右クリック: 複製・削除など　Ctrl+ホイール: 拡大縮小", SwingConstants.RIGHT);
        hint.setForeground(new Color(120, 125, 135));
        hint.setMinimumSize(new Dimension(0, 0));
        th.add(hint, BorderLayout.CENTER);
        tl.add(th, BorderLayout.NORTH);
        timelineScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(222, 224, 230)));
        tl.add(timelineScroll, BorderLayout.CENTER);
        JSplitPane work = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, tl);
        work.setResizeWeight(0.62);
        work.setDividerLocation(560);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, work);
        main.setDividerLocation(330);
        // ---- status bar
        JPanel sb = new JPanel(new BorderLayout(12, 0));
        sb.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(222, 224, 230)), BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        sb.add(status, BorderLayout.CENTER);
        layersLabel.setForeground(new Color(120, 125, 135));
        sb.add(layersLabel, BorderLayout.EAST);
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(sb, BorderLayout.SOUTH);
        // mouse back / forward buttons anywhere in the window
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (!(ev instanceof java.awt.event.MouseEvent)) return;
            java.awt.event.MouseEvent me = (java.awt.event.MouseEvent) ev;
            if (me.getID() != java.awt.event.MouseEvent.MOUSE_RELEASED || me.getButton() < 4) return;
            Window w = me.getComponent() instanceof Window ? (Window) me.getComponent() : SwingUtilities.getWindowAncestor(me.getComponent());
            if (w != this) return;
            if (me.getButton() == 4) goBack(); else if (me.getButton() == 5) goForward();
        }, AWTEvent.MOUSE_EVENT_MASK);
        refreshCrumbs();
    }

    // ================================================================= v5: history (back / forward)
    static final class Loc {
        /** 0 mech page, 1 weapon, 2 waz tree node (ptr), 3 shared waz (no skill). */
        final int kind, mechId, wazIdx; final String key, ptr;
        Loc(int kind, int mechId, int wazIdx, String key, String ptr) { this.kind = kind; this.mechId = mechId; this.wazIdx = wazIdx; this.key = key; this.ptr = ptr; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Loc)) return false;
            Loc l = (Loc) o;
            return l.kind == kind && l.mechId == mechId && l.wazIdx == wazIdx && Objects.equals(l.key, key) && Objects.equals(l.ptr, ptr);
        }
        @Override public int hashCode() { return Objects.hash(kind, mechId, wazIdx, key, ptr); }
    }

    final class History {
        final List<Loc> list = new ArrayList<>();
        int pos = -1;
        boolean restoring;
        void push(Loc l) {
            if (restoring || l == null) return;
            if (pos >= 0 && list.get(pos).equals(l)) return;
            while (list.size() > pos + 1) list.remove(list.size() - 1);
            list.add(l);
            if (list.size() > 300) list.remove(0);
            pos = list.size() - 1;
            refreshNav();
        }
        void clear() { list.clear(); pos = -1; refreshNav(); }
    }

    int ownerMechId() { return curMech != null && curMech.wazIdx == curWazIdx ? curMech.id : -1; }

    void record(int kind, String key, String ptr) {
        if (p == null || curWazIdx < 0 && kind != 0) return;
        history.push(new Loc(kind, kind == 0 ? (curMech == null ? -1 : curMech.id) : ownerMechId(), curWazIdx, key, ptr));
        refreshCrumbs();
    }

    void refreshNav() {
        backBtn.setEnabled(history.pos > 0);
        fwdBtn.setEnabled(history.pos >= 0 && history.pos < history.list.size() - 1);
    }

    void goBack() { if (history.pos > 0 && !busy) { history.pos--; restore(history.list.get(history.pos)); } }
    void goForward() { if (history.pos >= 0 && history.pos < history.list.size() - 1 && !busy) { history.pos++; restore(history.list.get(history.pos)); } }

    void restore(Loc l) {
        refreshNav();
        Runnable apply = () -> {
            history.restoring = true;
            try {
                switch (l.kind) {
                    case 0: showMechPage(); break;
                    case 1: if (curMek != null && curMek.tree.path("mekWeaponInfoMap").has(l.key)) selectWeapon(l.key); break;
                    case 2: if (tree != null) tree.selectPtr(l.ptr); break;
                    default: showSharedRoot();
                }
            } finally { history.restoring = false; refreshCrumbs(); }
        };
        if (l.mechId >= 0 && l.mechId < p.mechs.size()) {
            Project.Mech m = p.mechs.get(l.mechId);
            if (curMech != m || curWazIdx != m.wazIdx || curWaz == null) { selectMech(m, apply); return; }
        } else if (l.wazIdx != curWazIdx || curWaz == null) { openWaz(l.wazIdx, -1, apply); return; }
        apply.run();
    }

    // ================================================================= v5: breadcrumbs ("where am I")
    JButton crumb(String text, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.setMargin(new Insets(2, 4, 2, 4));
        b.addActionListener(e -> r.run());
        return b;
    }

    void addCrumb(String text, Runnable r, boolean bold) {
        if (crumbs.getComponentCount() > 0) { JLabel sep = new JLabel(" › "); sep.setForeground(new Color(150, 155, 165)); crumbs.add(sep); }
        JButton b = crumb(text, r);
        if (bold) b.setFont(b.getFont().deriveFont(Font.BOLD));
        crumbs.add(b);
    }

    void refreshCrumbs() {
        crumbs.removeAll();
        if (p == null || curWaz == null) {
            JLabel l = new JLabel(p == null ? "ゲームフォルダを開いてください（ファイル → ゲームフォルダを開く）" : "機体を選んでください");
            l.setForeground(new Color(120, 125, 135));
            crumbs.add(l);
        } else {
            boolean own = ownerMechId() >= 0;
            if (own) addCrumb(curMech.mekName, this::showMechPage, propsKind == 1);
            else addCrumb("共通: " + p.wazLabel(curWazIdx), this::showSharedRoot, false);
            SkillTree.N n = tree == null ? null : tree.selected();
            String wk = curWeaponKey;
            if (own && wk != null && curMek != null && curMek.tree.path("mekWeaponInfoMap").has(wk)) {
                JsonNode w = curMek.tree.path("mekWeaponInfoMap").path(wk);
                int wsi = w.path("wazSequence").asInt();
                if (propsKind == 2 || n != null && n.kind >= 1 && n.si == wsi)
                    addCrumb("武装 " + wk + " " + w.path("weaponName").asText(), () -> selectWeapon(wk), propsKind == 2);
            }
            if (propsKind == 2 && wk != null) {
                int wsi = curMek.tree.path("mekWeaponInfoMap").path(wk).path("wazSequence").asInt();
                addCrumb("技 " + wsi + " " + WazNav.skillName(curWaz.tree, wsi), () -> tree.select(wsi, -1, -1, -1), false);
            } else if (propsKind == 3 && n != null && n.kind >= 1) {
                addCrumb("技 " + n.si + " " + WazNav.skillName(curWaz.tree, n.si), () -> tree.select(n.si, -1, -1, -1), n.kind == 1);
                if (n.kind >= 2) addCrumb("ターン " + n.t, () -> tree.select(n.si, n.t, -1, -1), n.kind == 2);
                if (n.kind >= 3) {
                    String up = n.kind == 3 ? n.ptr : n.ptr.substring(0, n.ptr.indexOf("/skillInfoObjectList"));
                    int slot = curWaz.tree.at(up + "/unitQuantity").asInt();
                    String txt = "[" + slot + "] " + p.fields.slotName(slot);
                    if (n.kind == 4) { JsonNode o = Json.at(curWaz.tree, n.ptr); if (o != null) txt += "　" + o.path("startFrame").asInt() + "〜" + o.path("endFrame").asInt() + "F"; }
                    addCrumb(txt, () -> tree.selectPtr(n.ptr), true);
                }
            }
        }
        crumbs.revalidate();
        crumbs.repaint();
        refreshTimelineTitle();
    }

    void refreshTimelineTitle() {
        if (timeline == null || timeline.seq() == null || curWaz == null) { timelineTitle.setText("タイムライン"); return; }
        SkillSeq q = timeline.seq();
        int si = timeline.skill();
        timelineTitle.setText("タイムライン　技 " + si + " " + WazNav.skillName(curWaz.tree, si) + "　" + (q.isAll() ? "全ターン（ジャンプ無視）" : "ターン " + q.turnFilter) + "　" + q.total + "F");
    }

    // ================================================================= v5: pages
    void showMechPage() {
        if (curMech == null || curMek == null) return;
        if (curWazIdx != curMech.wazIdx || tree.doc != curWaz) { selectMech(curMech, null); return; }
        propsKind = 1;
        overview.showMech(curMech, curMek, curWaz, weaponPane);
        centerTabs.setSelectedIndex(0);
        timeline.show(null, -1, -1, -1, null);
        setProps(mechPage());
        outline.select(mechTab == 1 ? Outline.AI : Outline.STATUS, null, -1);
        record(0, null, null);
    }

    void showSharedRoot() {
        if (curWaz == null) return;
        propsKind = 0;
        overview.showText("共通: " + p.wazLabel(curWazIdx) + "（" + curWaz.name + "、技 " + curWaz.tree.path("skillList").size() + "）\n"
                + "ここでの変更はこの技を使うすべての機体に効きます。左の一覧で技を選ぶと、どこから使われているかが表示されます。");
        timeline.show(null, -1, -1, -1, null);
        setProps(Form.note("共通の弾・エフェクト: " + p.wazLabel(curWazIdx)));
        outline.select(-1, null, -1);
        record(3, null, null);
    }

    void onOutline(Outline.Item it) {
        switch (it.kind) {
            case Outline.STATUS: mechTab = 0; showMechPage(); break;
            case Outline.AI: mechTab = 1; showMechPage(); break;
            case Outline.WEAPON: selectWeapon(it.key); break;
            case Outline.SKILL: tree.select(it.si, -1, -1, -1); break;
            default:
        }
    }

    void outlineMenu(Outline.Item it, java.awt.event.MouseEvent e) {
        JPopupMenu m = new JPopupMenu();
        if (it.kind == Outline.WEAPON) {
            m.add(item("武装を複製（末尾に追加）", null, () -> weaponAction("dupEnd")));
            m.add(item("上へ", null, () -> weaponAction("up")));
            m.add(item("下へ", null, () -> weaponAction("down")));
            m.addSeparator();
            m.add(item("武装を削除…", null, () -> weaponAction("del")));
        } else if (it.kind == Outline.SKILL) {
            m.add(item("技を複製（直後に挿入）（Ctrl+D）", null, () -> structOnTree("dup")));
            m.add(item("技を複製（末尾に追加）（Ctrl+Alt+D）", null, () -> structOnTree("dupEnd")));
            m.add(item("新しい技を直後に追加", null, () -> structOnTree("add")));
            m.add(item("上へ（Alt+↑）", null, () -> structOnTree("up")));
            m.add(item("下へ（Alt+↓）", null, () -> structOnTree("down")));
            m.addSeparator();
            m.add(item("技を削除…（Delete）", null, () -> structOnTree("del")));
        } else return;
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    void timelineMenu(WazNav.Ev ev, int turn, java.awt.event.MouseEvent e) {
        if (curWaz == null) return;
        JPopupMenu m = new JPopupMenu();
        if (ev != null) {
            timelineClick = true;
            try { tree.select(ev.skill, ev.turn, ev.unitIdx, ev.objIdx); } finally { timelineClick = false; }
            int[] r = Timeline.refOf(ev.node);
            if (r != null) {
                int w = r[0] == -1 ? curWazIdx : r[0];
                List<String> names = p.skillNames(w);
                String nm = names != null && r[1] >= 0 && r[1] < names.size() ? names.get(r[1]) : "?";
                m.add(item("生成先の技を開く: " + p.wazLabel(w) + " 技 " + r[1] + " " + nm, null, () -> openSkill(w, r[1])));
                m.addSeparator();
            }
            m.add(item("イベントを複製（Ctrl+D）", null, () -> structOnTree("dup")));
            m.add(item("同じスロットにイベントを追加…", null, () -> structOnTree("add")));
            m.add(item("上へ（Alt+↑）", null, () -> structOnTree("up")));
            m.add(item("下へ（Alt+↓）", null, () -> structOnTree("down")));
            m.addSeparator();
            m.add(item("イベントを削除…（Delete）", null, () -> structOnTree("del")));
        } else if (turn >= 0) {
            int si = timeline.skill();
            tree.select(si, turn, -1, -1);
            m.add(item("ターン " + turn + " を複製", null, () -> structOnTree("dup")));
            m.add(item("空のターンを直後に追加", null, () -> structOnTree("add")));
            m.add(item("イベントを追加…", null, () -> addEventDialog(si, turn, -1)));
            m.add(item("前へ（Alt+↑）", null, () -> structOnTree("up")));
            m.add(item("後へ（Alt+↓）", null, () -> structOnTree("down")));
            m.addSeparator();
            m.add(item("ターン " + turn + " を削除…", null, () -> structOnTree("del")));
        } else return;
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    void setProps(JComponent c) {
        propHolder.removeAll();
        propHolder.add(c instanceof JTabbedPane || c instanceof JScrollPane ? c : Editors.scroll(c), BorderLayout.CENTER);
        propHolder.revalidate();
        propHolder.repaint();
    }

    @Override public void status(String msg) { status.setText(msg); }

    void refreshButtons() {
        boolean has = p != null;
        undoBtn.setEnabled(has && p.undo.canUndo());
        redoBtn.setEnabled(has && p.undo.canRedo());
        undoBtn.setToolTipText(has && p.undo.canUndo() ? "元に戻す: " + p.undo.undoLabel() : null);
        redoBtn.setToolTipText(has && p.undo.canRedo() ? "やり直し: " + p.undo.redoLabel() : null);
        exportBtn.setEnabled(has);
        int n = has ? p.dirtyDocs().size() : 0;
        changesBtn.setText("変更 " + n);
        changesBtn.setEnabled(has);
        changesBtn.putClientProperty("FlatLaf.style", n > 0 ? "foreground: #b45f00; font: bold" : null);
        refreshNav();
        setTitle("BsdxWorkshop — BSDX 機体調整ツール" + (n > 0 ? "  （変更あり: " + n + " ファイル）" : ""));
    }

    // ================================================================= background helper
    <T> void bg(String msg, Supplier<T> work, java.util.function.Consumer<T> done) {
        busy = true;
        status(msg);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return work.get(); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                busy = false;
                try { done.accept(get()); }
                catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    c.printStackTrace();
                    status("エラー: " + c.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this, String.valueOf(c.getMessage()), "エラー", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    static <T> T unchecked(java.util.concurrent.Callable<T> c) {
        try { return c.call(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
    }

    // ================================================================= project
    public void openProject() {
        Path game = settings.gameDir == null || settings.gameDir.isBlank() ? null : Path.of(settings.gameDir);
        Path ov = settings.overlay == null || settings.overlay.isBlank() ? null : Path.of(settings.overlay);
        if (p != null) { try { p.fs.close(); } catch (Exception ignored) {} p = null; }
        if (game != null) Settings.remember(settings.recentGameDirs, game.toString());
        if (ov != null) Settings.remember(settings.recentOverlays, ov.toString());
        saveSettings();
        rebuildRecent();
        curMech = null; curMek = curWaz = null; curWazIdx = -1; curWeaponKey = null;
        history.clear();
        bg("読み込み中…", () -> unchecked(() -> Loader.open(codec, fields, game, settings.mountGameUpdate3to7, ov, null)), this::setProject);
    }

    public void setProject(Project np) {
        p = np;
        p.undo.addListener(this::onEdit);
        ctx = new Form.Ctx(p, this);
        if (tree != null) center.remove(tree);
        center.removeAll();
        tree = new SkillTree(p);
        tree.onSelect = this::onTreeSelect;
        installTreeMenu();
        center.add(tree, BorderLayout.CENTER);
        outline = new Outline(p);
        outline.onSelect = this::onOutline;
        outline.onContext = this::outlineMenu;
        outlineHolder.removeAll();
        outlineHolder.add(outline, BorderLayout.CENTER);
        outlineHolder.revalidate();
        overview = new Overview(p, new Overview.Links() {
            public void openWeapon(String key) { selectWeapon(key); }
            public void openSkill(int wazIdx, int si) { MainFrame.this.openSkill(wazIdx, si); }
            public void openMechPage() { showMechPage(); }
        });
        overviewHolder.removeAll();
        overviewHolder.add(overview, BorderLayout.CENTER);
        overviewHolder.revalidate();
        timeline = new Timeline(p);
        timeline.onOpenRef = r -> openSkill(r[0] == -1 ? curWazIdx : r[0], r[1]);
        timeline.onContext = this::timelineMenu;
        timeline.onSelect = ev -> { timelineClick = true; try { tree.select(ev.skill, ev.turn, ev.unitIdx, ev.objIdx); } finally { timelineClick = false; } };
        timeline.onTurn = (s2, t) -> tree.select(s2, t, -1, -1);
        timeline.onSlot = a -> { timelineClick = true; try { tree.select(a[0], a[1], a[2], -1); } finally { timelineClick = false; } };
        timelineScroll.setViewportView(timeline);
        center.revalidate();
        mechModel.rows = new ArrayList<>();
        for (Project.Mech m : p.mechs) if (m.mekFile != null) mechModel.rows.add(m);
        mechModel.fireTableDataChanged();
        fitColumns(mechTable);
        sharedModel.rows = new ArrayList<>();
        for (int i = 0; i < p.wazaList().size(); i++) if (p.wazFile(i) != null) sharedModel.rows.add(i);
        sharedModel.fireTableDataChanged();
        fitColumns(sharedTable);
        weaponModel.set(null, null);
        StringBuilder sb = new StringBuilder("レイヤ（優先順）: ");
        for (var l : p.fs.layers()) sb.append(l.label()).append("  ");
        layersArea.setText(sb.toString().trim());
        layersLabel.setText(sb.toString().trim());
        layersLabel.setToolTipText(sb.toString().trim());
        boolean sys = p.fs.layers().stream().anyMatch(l -> l.label().equalsIgnoreCase("System.pac"));
        status("読み込み完了: 機体 " + mechModel.rows.size() + "、waz " + sharedModel.rows.size() + (sys ? "" : "（System.pac が無いためスプライトのプレビューは機体 spm が pac にある場合のみ）"));
        setProps(Form.note("機体を選んでください。\n" + sb + "\n" + String.join("\n", p.log)));
        overview.showText("左の一覧から機体を選んでください。共通の弾・エフェクトは「共通の弾・エフェクト」タブにあります。");
        refreshButtons();
        refreshCrumbs();
        search = new SearchIndex(p);
        final Project fp = p;
        new SwingWorker<RefIndex, Void>() {
            @Override protected RefIndex doInBackground() { RefIndex r = fp.buildRefIndex(); search.build(); return r; }
            @Override protected void done() { try { RefIndex r = get(); status(status.getText() + "　参照索引: " + r.refCount + " 件、検索の準備完了"); if (overview != null) overview.refreshInfo(); } catch (Exception ignored) {} }
        }.execute();
        if (settings.lastMech >= 0) for (int r = 0; r < mechModel.rows.size(); r++)
            if (mechModel.rows.get(r).id == settings.lastMech) {
                int vr = mechTable.convertRowIndexToView(r);
                mechTable.getSelectionModel().setSelectionInterval(vr, vr);
                mechTable.scrollRectToVisible(mechTable.getCellRect(vr, 0, true));
                break;
            }
    }

    void onEdit() {
        UndoStack.Edit le = p.undo.lastEdit;
        boolean structural = le != null && le.structural;
        boolean wazStruct = false, mekStruct = false;
        if (structural) for (UndoStack.Change c : le.changes) { if (c.doc.ext.equals("waz")) wazStruct = true; if (c.doc.ext.equals("mek")) mekStruct = true; }
        p.syncRefIndex();
        if (wazStruct && tree != null) {
            // tree / timeline / page pointers shifted: rebuild and re-select (this rebuilds the property page)
            propHolder.removeAll();
            tree.rebuildKeep();
            if (tree.selected() == null) setProps(Form.note("構造を変更しました。"));
        } else if (mekStruct && propsKind == 2 && curMek != null) {
            if (curMek.tree.path("mekWeaponInfoMap").has(curWeaponKey)) selectWeapon(curWeaponKey);
            else setProps(mechPage());
        } else if (ctx != null) ctx.reloadAll();
        if (tree != null) tree.tree.repaint();
        if (timeline != null) timeline.refresh();
        if (overview != null) overview.refreshInfo();
        if (outline != null) { if (mekStruct || wazStruct) outline.rebuild(); else outline.repaintLabels(); }
        if (curMech != null) p.refreshMech(curMech);
        mechModel.fireTableRowsUpdated(0, Math.max(0, mechModel.getRowCount() - 1));
        weaponModel.recompute();
        refreshButtons();
        refreshCrumbs();
        if (p.undo.canUndo()) status("変更: " + p.undo.undoLabel());
    }

    void undo() { if (p != null && p.undo.canUndo()) { String l = p.undo.undoLabel(); p.undo.undo(); status("元に戻しました: " + l); } }
    void redo() { if (p != null && p.undo.canRedo()) { String l = p.undo.redoLabel(); p.undo.redo(); status("やり直しました: " + l); } }

    Form.Ctx newCtx() { ctx = new Form.Ctx(p, this); return ctx; }

    /** Mech page: status + AI table tabs. */
    JComponent mechPage() {
        propsKind = 1;
        Form.Ctx c = newCtx();
        JTabbedPane t = new JTabbedPane();
        t.addTab("機体ステータス", Editors.scroll(Editors.mekBasic(c, curMek)));
        t.addTab("AI 表（" + curMek.tree.path("mekAiInfoList").size() + " 種類）", new AiEditor(c, curMech, curMek, this::structResult));
        if (mechTab < t.getTabCount()) t.setSelectedIndex(mechTab);
        t.addChangeListener(e -> mechTab = t.getSelectedIndex());
        return t;
    }

    void openAiTab() {
        if (curMech == null || curMek == null) { status("機体を選んでください"); return; }
        mechTab = 1;
        showMechPage();
    }

    // ================================================================= selection
    void selectMech(Project.Mech m, Runnable after) {
        if (busy) return;
        bg("読み込み中: " + m.mekFile, () -> unchecked(() -> {
            FileDoc mek = p.doc(m.mekFile);
            String wf = p.wazFile(m.wazIdx);
            FileDoc waz = wf == null ? null : p.doc(wf);
            return new FileDoc[]{mek, waz};
        }), d -> {
            curMech = m; curMek = d[0]; curWaz = d[1]; curWazIdx = m.wazIdx; curWeaponKey = null;
            weaponModel.set(curMek, curWaz);
            tree.setWaz(curWaz, curWazIdx, m, curWaz == null ? "waz が見つかりません" : "機体 " + m.id + " " + m.mekName + " / " + m.pilot + " — " + m.mekFile + "（元: " + curMek.sourceLabel + "）");
            outline.setMech(m, curMek, curWaz);
            syncMechRow(m);
            settings.lastMech = m.id;
            status(m.mekFile + " / " + (curWaz == null ? "-" : curWaz.name + " 技 " + curWaz.tree.path("skillList").size()));
            if (after != null) after.run();
            else if (curWaz == null) { timeline.show(null, -1, -1, -1, null); setProps(mechPage()); overview.showText("waz が見つかりません"); }
            else { mechTab = 0; showMechPage(); }
        });
    }

    /** Selects the mech's row in the list without reloading. */
    void syncMechRow(Project.Mech m) {
        recomputing = true;
        try {
            for (int r = 0; r < mechModel.rows.size(); r++) if (mechModel.rows.get(r) == m) {
                int vr = mechTable.convertRowIndexToView(r);
                if (vr >= 0) { mechTable.getSelectionModel().setSelectionInterval(vr, vr); mechTable.scrollRectToVisible(mechTable.getCellRect(vr, 0, true)); }
                else mechTable.clearSelection();
                return;
            }
            mechTable.clearSelection();
        } finally { recomputing = false; }
    }

    void selectWeapon(String key) {
        if (curMek == null) return;
        JsonNode w = curMek.tree.path("mekWeaponInfoMap").path(key);
        int si = w.path("wazSequence").asInt();
        if (curWaz != null && tree.doc == curWaz) {
            tree.silent = true;
            tree.select(si, -1, -1, -1);
            tree.silent = false;
            timeline.showSkill(curWaz, si, curMech.spmIdx, null);
        }
        curWeaponKey = key;
        setProps(Editors.weapon(newCtx(), curMek, key, curWaz, curWazIdx));
        propsKind = 2;
        if (curWaz != null && si >= 0 && si < curWaz.tree.path("skillList").size()) {
            overview.showSkill(new Form.Ctx(p, this), curWaz, curWazIdx, si, curMech, curMek, -1);
            centerTabs.setSelectedIndex(0);
        }
        int row = weaponModel.keys.indexOf(key);
        recomputing = true;
        try { if (row >= 0) weaponTable.getSelectionModel().setSelectionInterval(row, row); } finally { recomputing = false; }
        outline.select(Outline.WEAPON, key, -1);
        record(1, key, null);
    }

    /** Opens a waz (shared or a mech's own) in the tree and selects a skill; after (if given) runs instead of the default page. */
    void openWaz(int wazIdx, int skill, Runnable after) {
        if (busy) return;
        String wf = p.wazFile(wazIdx);
        if (wf == null) { status("waz " + wazIdx + " は登録されていません"); return; }
        bg("読み込み中: " + wf, () -> unchecked(() -> p.doc(wf)), d -> {
            Project.Mech owner = p.mechOfWaz(wazIdx);
            if (owner != null && (curMech == null || curMech.wazIdx != wazIdx)) {
                curMech = owner;
                try { curMek = p.doc(owner.mekFile); } catch (Exception e) { curMek = null; }
                weaponModel.set(curMek, d);
            } else if (owner == null) {
                curMech = null; curMek = null;
                weaponModel.set(null, null);
            }
            curWaz = d; curWazIdx = wazIdx; curWeaponKey = null;
            String banner = owner != null ? "機体 " + owner.mekName + " の waz: " + d.name
                    : "⚠ 共通 waz: " + p.wazLabel(wazIdx) + " — ここでの変更はこの技を使うすべての機体に効きます。技を選ぶと参照元が表示されます。";
            tree.setWaz(d, wazIdx, owner, banner);
            if (owner != null) { outline.setMech(owner, curMek, d); syncMechRow(owner); }
            else {
                outline.setShared(wazIdx, d);
                recomputing = true;
                try { int r = sharedModel.rows.indexOf(wazIdx); if (r >= 0) { sharedTable.getSelectionModel().setSelectionInterval(r, r); sharedTable.scrollRectToVisible(sharedTable.getCellRect(r, 0, true)); } }
                finally { recomputing = false; }
            }
            timeline.show(null, -1, -1, -1, null);
            status(d.name + "（" + p.wazLabel(wazIdx) + "）技 " + d.tree.path("skillList").size());
            if (after != null) after.run();
            else if (skill >= 0) tree.select(skill, -1, -1, -1);
            else if (owner != null) showMechPage();
            else showSharedRoot();
        });
    }

    @Override public void openSkill(int wazIdx, int skill) {
        if (curWaz != null && wazIdx == curWazIdx) tree.select(skill, -1, -1, -1);
        else openWaz(wazIdx, skill, null);
    }

    void onTreeSelect(SkillTree.N n) {
        if (curWaz == null) return;
        int spm = curMech != null && curMech.wazIdx == curWazIdx ? curMech.spmIdx : -1;
        Project.Mech mech = curMech != null && curMech.wazIdx == curWazIdx ? curMech : null;
        if (n.kind == 0) {
            if (curMek != null && mech != null) showMechPage(); else showSharedRoot();
            return;
        }
        propsKind = 3;
        if (curWeaponKey != null && (curMek == null || curMek.tree.path("mekWeaponInfoMap").path(curWeaponKey).path("wazSequence").asInt(-1) != n.si)) curWeaponKey = null;
        FileDoc ownMek = mech != null ? curMek : null;
        switch (n.kind) {
            case 1:
                timeline.showSkill(curWaz, n.si, spm, null);
                overview.showSkill(new Form.Ctx(p, this), curWaz, curWazIdx, n.si, mech, ownMek, -1);
                setProps(Editors.skill(newCtx(), curWaz, curWazIdx, n.si, mech, -1, false));
                break;
            case 2:
                timeline.show(curWaz, n.si, n.t, spm, n.ptr);
                overview.showSkill(new Form.Ctx(p, this), curWaz, curWazIdx, n.si, mech, ownMek, n.t);
                setProps(Editors.skill(newCtx(), curWaz, curWazIdx, n.si, mech, n.t, false));
                break;
            case 3: {
                showTimelineFor(n, spm);
                if (!overview.showsSkill(curWaz, n.si, overview.turn)) overview.showSkill(new Form.Ctx(p, this), curWaz, curWazIdx, n.si, mech, ownMek, -1);
                int slot = curWaz.tree.at(n.ptr + "/unitQuantity").asInt();
                Fields.Slot sl = fields.slots.get(slot);
                setProps(Form.note("[" + slot + "] " + (sl == null ? "" : sl.nameJa + " — " + sl.cls + "（" + Editors.confJa(sl.confidence) + "）"
                        + (sl.ux.isEmpty() ? "" : "\n体感: " + sl.ux + "（" + sl.uxConf + "）") + "\n" + sl.semantics)
                        + "\nイベント " + curWaz.tree.at(n.ptr + "/skillInfoObjectList").size() + " 個。イベントを選ぶと編集できます。"));
                break;
            }
            default:
                showTimelineFor(n, spm);
                if (!overview.showsSkill(curWaz, n.si, overview.turn)) overview.showSkill(new Form.Ctx(p, this), curWaz, curWazIdx, n.si, mech, ownMek, -1);
                setProps(Editors.event(newCtx(), curWaz, curWazIdx, mech, n.ptr));
                status(n.ptr);
        }
        if (centerTabs.getSelectedIndex() != 1) centerTabs.setSelectedIndex(0);
        outline.selectSkill(n.si, curWeaponKey);
        record(2, null, n.ptr);
    }

    /** Slot / event selected: keep the all-turns layout when it already shows this skill (or the click came from it). */
    void showTimelineFor(SkillTree.N n, int spm) {
        if (timelineClick || timeline.isSkillMode() && timeline.showsSkill(curWaz, n.si)) timeline.showSkill(curWaz, n.si, spm, n.ptr);
        else timeline.show(curWaz, n.si, n.t, spm, n.ptr);
        if (n.ptr != null) SwingUtilities.invokeLater(() -> {
            Point c = timeline.centerOf(n.ptr);
            if (c != null) timeline.scrollRectToVisible(new Rectangle(c.x - 40, c.y - 20, 120, 40));
        });
    }

    @Override public void playhead(FileDoc waz, int si, SkillSeq seq, int g) {
        if (timeline == null || !timeline.showsSkill(waz, si)) return;
        SkillSeq ts = timeline.seq();
        if (ts.turnFilter != seq.turnFilter) { timeline.setPlayhead(-1); return; }
        timeline.setPlayhead(g);
    }

    // ================================================================= dialogs
    void chooseGameDir() {
        JFileChooser fc = new JFileChooser(settings.gameDir);
        fc.setDialogTitle("ゲームフォルダ（BaldrSky.exe と Dat.pac があるフォルダ）を選択 — 読み取り専用で開きます");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || !confirmDiscard()) return;
        settings.gameDir = fc.getSelectedFile().getAbsolutePath();
        saveSettings();
        openProject();
    }

    void chooseOverlay(boolean dir) {
        JFileChooser fc = new JFileChooser(settings.overlay.isBlank() ? settings.projectRoot : settings.overlay);
        fc.setDialogTitle(dir ? "MOD のフォルダ（pac を展開したファイルが並んだフォルダ）" : "MOD の pac（例: Update3.pac）");
        fc.setFileSelectionMode(dir ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || !confirmDiscard()) return;
        settings.overlay = fc.getSelectedFile().getAbsolutePath();
        saveSettings();
        openProject();
    }

    void saveSettings() {
        try { settings.save(); } catch (Exception e) { status("設定を保存できません: " + e.getMessage()); }
    }

    boolean confirmDiscard() {
        if (p == null || p.dirtyDocs().isEmpty()) return true;
        return JOptionPane.showConfirmDialog(this, "書き出していない変更があります（" + p.dirtyDocs().size() + " ファイル）。破棄しますか？", "確認",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    void quit() {
        if (!confirmDiscard()) return;
        settings.maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        if (!settings.maximized) { Rectangle b = getBounds(); settings.windowBounds = b.x + "," + b.y + "," + b.width + "," + b.height; }
        saveSettings();
        dispose();
        System.exit(0);
    }

    void showChanges() {
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        for (FileDoc d : p.dirtyDocs()) {
            sb.append("== ").append(d.name).append("（元: ").append(d.sourceLabel).append("）\n");
            d.changes().forEach((k, v) -> sb.append("  ").append(k).append(" : ").append(v).append('\n'));
        }
        sb.append("\n[操作履歴]\n");
        for (UndoStack.Edit e : p.undo.history()) sb.append("  ").append(e.label).append('\n');
        JTextArea a = new JTextArea(sb.length() == 0 ? "変更はありません" : sb.toString(), 24, 90);
        a.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(a), "変更の一覧", JOptionPane.PLAIN_MESSAGE);
    }

    void settingsDialog() {
        JTextField game = new JTextField(settings.gameDir, 40), proj = new JTextField(settings.projectRoot, 40), exp = new JTextField(settings.exportDir, 40), ov = new JTextField(settings.overlay, 40);
        JCheckBox outside = new JCheckBox("PROJECT フォルダ外への書き出しを許可する（非推奨。ゲームフォルダへはどの設定でも書き込みません）", settings.allowOutsideProject);
        JCheckBox upd = new JCheckBox("ゲームフォルダの Update3〜7.pac（インストール済み MOD）も読み込む", settings.mountGameUpdate3to7);
        JCheckBox spm = new JCheckBox("書き出し時にスプライトのアニメ/パターン範囲もチェック（spm がある場合）", settings.spmCheck);
        Form.Grid g = new Form.Grid();
        g.add(new JLabel("ゲームフォルダ（読み取り専用）"), game);
        g.add(new JLabel("PROJECT フォルダ"), proj);
        g.add(new JLabel("書き出し先"), exp);
        g.add(new JLabel("オーバーレイ（MOD フォルダ / pac）"), ov);
        g.full(outside); g.full(upd); g.full(spm);
        g.full(new JLabel("設定ファイル: " + settings.file()));
        if (JOptionPane.showConfirmDialog(this, g, "設定", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean reload = !game.getText().equals(settings.gameDir) || !ov.getText().equals(settings.overlay) || upd.isSelected() != settings.mountGameUpdate3to7;
        if (outside.isSelected() && !settings.allowOutsideProject
                && JOptionPane.showConfirmDialog(this, "PROJECT フォルダ外への書き出しを許可しますか？", "確認", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            outside.setSelected(false);
        settings.gameDir = game.getText().trim(); settings.projectRoot = proj.getText().trim(); settings.exportDir = exp.getText().trim();
        settings.overlay = ov.getText().trim(); settings.allowOutsideProject = outside.isSelected(); settings.mountGameUpdate3to7 = upd.isSelected(); settings.spmCheck = spm.isSelected();
        saveSettings();
        if (reload && confirmDiscard()) openProject();
    }

    void exportDialog(boolean write) {
        if (p == null || busy) return;
        bg("検証中（生成→再パース→比較、mek_check）…", () -> new Exporter(p, settings.spmCheck).prepare(), plan -> showPlan(plan, write));
    }

    void showPlan(Exporter.Plan plan, boolean write) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.ok() ? "■ 検証 OK\n" : "■ 検証 NG — 書き出せません\n");
        plan.errors.forEach(e -> sb.append("エラー: ").append(e).append('\n'));
        plan.blocking.forEach(v -> sb.append("違反: ").append(v).append('\n'));
        plan.report.forEach(r -> sb.append(r).append('\n'));
        plan.warnings.forEach(w -> sb.append("注意: ").append(w).append('\n'));
        plan.info.forEach(v -> sb.append("情報: ").append(v).append('\n'));
        try {
            List<bsdx.workshop.pac.PacWriter.Item> items = new Exporter(p, false).items(plan);
            sb.append("\nUpdate3.pac の中身: ").append(items.size()).append(" ファイル（オーバーレイ ").append(p.fs.getOverlay() == null ? 0 : p.fs.getOverlay().names().size())
                    .append(" + 変更 ").append(plan.generated.size()).append("）\n");
        } catch (Exception e) { sb.append(e).append('\n'); }
        JTextArea a = new JTextArea(sb.toString(), 18, 90);
        a.setEditable(false);
        a.setLineWrap(true);
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JScrollPane(a), BorderLayout.CENTER);
        JTextField dir = new JTextField(settings.exportDir, 50);
        JLabel guard = new JLabel(" ");
        Runnable chk = () -> {
            String why = ExportGuard.check(Path.of(dir.getText().trim()), settings);
            guard.setText(why == null ? "書き出し先 OK" : "<html><font color='#b00000'>" + Form.esc(why).replace("\n", "<br>") + "</font></html>");
        };
        dir.addActionListener(e -> chk.run());
        chk.run();
        if (write) {
            JPanel south = new JPanel(new BorderLayout());
            south.add(new JLabel("書き出し先フォルダ: "), BorderLayout.WEST);
            south.add(dir, BorderLayout.CENTER);
            south.add(guard, BorderLayout.SOUTH);
            panel.add(south, BorderLayout.SOUTH);
        }
        Object[] opts = write && plan.ok() ? new Object[]{"書き出す", "キャンセル"} : new Object[]{"閉じる"};
        int r = JOptionPane.showOptionDialog(this, panel, write ? "書き出し（Update3.pac）" : "検証結果", JOptionPane.DEFAULT_OPTION,
                plan.ok() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
        if (!(write && plan.ok() && r == 0)) return;
        Path d = Path.of(dir.getText().trim());
        String why = ExportGuard.check(d, settings);
        if (why != null) { JOptionPane.showMessageDialog(this, why, "書き出せません", JOptionPane.ERROR_MESSAGE); return; }
        settings.exportDir = d.toString();
        saveSettings();
        bg("書き出し中…", () -> unchecked(() -> new Exporter(p, settings.spmCheck).write(plan, d, settings, p.undo.history())), pac -> {
            status("書き出しました: " + pac);
            JOptionPane.showMessageDialog(this, "書き出しました:\n" + pac + "\n変更記録: " + pac.resolveSibling("Update3_changes.txt")
                    + "\n\nゲームに反映するには、この Update3.pac をご自身でゲームフォルダへ置いてください（既存の Update3.pac は退避してから）。");
        });
    }


    // ================================================================= v2: recent files
    void rebuildRecent() {
        if (recentMenu == null) return;
        recentMenu.removeAll();
        for (String o : settings.recentOverlays)
            recentMenu.add(item("オーバーレイ: " + o, null, () -> { if (confirmDiscard()) { settings.overlay = o; saveSettings(); openProject(); } }));
        if (!settings.recentOverlays.isEmpty()) recentMenu.addSeparator();
        for (String g : settings.recentGameDirs)
            recentMenu.add(item("ゲームフォルダ: " + g, null, () -> { if (confirmDiscard()) { settings.gameDir = g; saveSettings(); openProject(); } }));
        if (recentMenu.getItemCount() == 0) { JMenuItem none = new JMenuItem("（まだありません）"); none.setEnabled(false); recentMenu.add(none); }
    }

    // ================================================================= v2: structural editing
    void installTreeMenu() {
        tree.tree.addMouseListener(new java.awt.event.MouseAdapter() {
            void popup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                javax.swing.tree.TreePath tp = tree.tree.getPathForLocation(e.getX(), e.getY());
                if (tp == null) return;
                tree.tree.setSelectionPath(tp);
                SkillTree.N n = tree.selected();
                if (n == null || n.kind == 0) return;
                JPopupMenu m = new JPopupMenu();
                String what = n.kind == 1 ? "技" : n.kind == 2 ? "ターン" : n.kind == 3 ? "スロット" : "イベント";
                if (n.kind != 3) m.add(item(what + "を複製（直後に挿入）（Ctrl+D）", null, () -> structOnTree("dup")));
                if (n.kind == 1) m.add(item("技を複製（末尾に追加）（Ctrl+Alt+D）", null, () -> structOnTree("dupEnd")));
                m.add(item(n.kind == 1 ? "新しい技を直後に追加" : n.kind == 2 ? "空のターンを直後に追加" : "イベントを追加…", null, () -> structOnTree("add")));
                if (n.kind == 2) m.add(item("イベントを追加…", null, () -> addEventDialog(n.si, n.t, -1)));
                if (n.kind != 3) {
                    m.add(item("上へ（前へ）（Alt+↑）", null, () -> structOnTree("up")));
                    m.add(item("下へ（後へ）（Alt+↓）", null, () -> structOnTree("down")));
                    m.addSeparator();
                    m.add(item(what + "を削除…（Delete）", null, () -> structOnTree("del")));
                }
                m.show(tree.tree, e.getX(), e.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }
        });
    }

    /** Menu / shortcut entry: acts on the weapon table if it has the focus, else on the skill tree. */
    void structAction(String kind) {
        if (p == null) return;
        Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (fo != null && SwingUtilities.isDescendingFrom(fo, weaponTable)) weaponAction(kind);
        else if (fo != null && outline != null && SwingUtilities.isDescendingFrom(fo, outline) && outline.selected() != null && outline.selected().kind == Outline.WEAPON) weaponAction(kind);
        else if (fo instanceof javax.swing.text.JTextComponent && (kind.equals("del") || kind.equals("up") || kind.equals("down"))) return;
        else structOnTree(kind);
    }

    void structOnTree(String kind) {
        if (tree == null || curWaz == null) { status("技・ターン・イベントを選んでください"); return; }
        SkillTree.N n = tree.selected();
        if (n == null || n.kind == 0) { status("技・ターン・イベントを選んでください"); return; }
        Structure st = new Structure(p, settings.spmCheck);
        Structure.Result r;
        switch (n.kind) {
            case 1:
                switch (kind) {
                    case "dup": r = st.duplicateSkill(curWaz, n.si, false); break;
                    case "dupEnd": r = st.duplicateSkill(curWaz, n.si, true); break;
                    case "add": r = st.addSkill(curWaz, p.mechOfWaz(curWazIdx) == null ? curWaz.tree.path("skillList").size() : n.si + 1); break;
                    case "up": r = st.moveSkill(curWaz, n.si, -1); break;
                    case "down": r = st.moveSkill(curWaz, n.si, 1); break;
                    case "del":
                        if (!confirm("技 " + n.si + "「" + WazNav.skillName(curWaz.tree, n.si) + "」を削除しますか？\n後ろの技の番号は 1 つずつ詰まり、参照（武装・弾・技ジャンプ・基本動作表）は自動で付け替えます。")) return;
                        r = st.deleteSkill(curWaz, n.si); break;
                    default: return;
                }
                break;
            case 2:
                switch (kind) {
                    case "dup": case "dupEnd": r = st.duplicateTurn(curWaz, n.si, n.t); break;
                    case "add": r = st.addTurn(curWaz, n.si, n.t + 1); break;
                    case "up": r = st.moveTurn(curWaz, n.si, n.t, -1); break;
                    case "down": r = st.moveTurn(curWaz, n.si, n.t, 1); break;
                    case "del":
                        if (!confirm("技 " + n.si + " のターン " + n.t + " を削除しますか？（ターンジャンプ「指定」の番号は自動で付け替えます）")) return;
                        r = st.deleteTurn(curWaz, n.si, n.t); break;
                    default: return;
                }
                break;
            case 3:
                if (kind.equals("add")) { addEventDialog(n.si, n.t, curWaz.tree.at(n.ptr + "/unitQuantity").asInt()); return; }
                status("スロットではイベントの追加だけができます（イベントを選ぶと複製・削除・並べ替え）");
                return;
            default:
                switch (kind) {
                    case "dup": case "dupEnd": r = st.duplicateEvent(curWaz, n.ptr); break;
                    case "add": addEventDialog(n.si, n.t, curWaz.tree.at(n.ptr.substring(0, n.ptr.indexOf("/skillInfoObjectList")) + "/unitQuantity").asInt()); return;
                    case "up": r = st.moveEvent(curWaz, n.ptr, -1); break;
                    case "down": r = st.moveEvent(curWaz, n.ptr, 1); break;
                    case "del":
                        if (!confirm("このイベントを削除しますか？\n" + n.ptr)) return;
                        r = st.deleteEvent(curWaz, n.ptr); break;
                    default: return;
                }
        }
        structResult(r);
    }

    boolean confirm(String msg) {
        return JOptionPane.showConfirmDialog(this, msg, "確認", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    void addEventDialog(int si, int t, int slot) {
        JComboBox<Form.Item> sc = new JComboBox<>();
        for (Fields.Slot sl : fields.slots.values()) sc.addItem(new Form.Item(sl.slot, sl.slot + ": " + sl.nameJa + "（" + sl.cls + "）"));
        if (slot >= 0) for (int i = 0; i < sc.getItemCount(); i++) if (sc.getItemAt(i).value == slot) sc.setSelectedIndex(i);
        JTextField a = new JTextField("0", 5), b = new JTextField("" + Math.max(0, WazNav.turnLength(curWaz.tree, si, t) - 1), 5);
        Form.Grid g = new Form.Grid();
        g.add(new JLabel("スロット（イベントの種類）"), sc);
        g.add(new JLabel("開始フレーム"), a);
        g.add(new JLabel("終了フレーム"), b);
        g.full(Form.note("中身は既存データの同じスロットのイベント（この waz → 機体の waz → 全 waz の順で最初に見つかったもの）をコピーします。追加後に値を調整してください。"));
        if (JOptionPane.showConfirmDialog(this, g, "イベントを追加（技 " + si + " ターン " + t + "）", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            int s0 = Integer.parseInt(a.getText().trim()), s1 = Integer.parseInt(b.getText().trim());
            structResult(new Structure(p, settings.spmCheck).addEvent(curWaz, si, t, ((Form.Item) sc.getSelectedItem()).value, s0, Math.max(s0, s1)));
        } catch (NumberFormatException ex) { status("フレームは整数で入力してください"); }
    }

    void weaponAction(String kind) {
        if (curMech == null || curMek == null || curMech.mekFile == null || !curMek.name.equalsIgnoreCase(curMech.mekFile)) { status("機体を選んでください"); return; }
        int row = weaponTable.getSelectedRow();
        if (row < 0 && !kind.equals("add")) { status("武装を選んでください"); return; }
        int idx = row < 0 ? -1 : Integer.parseInt(weaponModel.keys.get(row));
        Structure st = new Structure(p, settings.spmCheck);
        Structure.Result r;
        switch (kind) {
            case "dup": case "dupEnd": case "add": r = st.duplicateWeapon(curMech, Math.max(0, idx), true); break;
            case "up": r = st.moveWeapon(curMech, idx, -1); break;
            case "down": r = st.moveWeapon(curMech, idx, 1); break;
            case "del": {
                String lock = Structure.weaponListLock(curMek.tree);
                if (lock != null) { JOptionPane.showMessageDialog(this, lock, "武装の削除", JOptionPane.WARNING_MESSAGE); return; }
                int uses = 0;
                for (JsonNode a : curMek.tree.path("mekAiInfoList")) for (JsonNode e : a.path("cpuEventList")) if (e.path("type").asInt() == 2 && e.path("mekWeaponInfoMapNo").asInt() == idx) uses++;
                JComboBox<Form.Item> repl = new JComboBox<>();
                JsonNode wm = curMek.tree.path("mekWeaponInfoMap");
                for (int i = 0; i < wm.size(); i++) if (i != idx) repl.addItem(new Form.Item(i, "#" + i + " " + wm.path("" + i).path("weaponName").asText()));
                JRadioButton drop = new JRadioButton("AI の攻撃イベントも削除する", true), re = new JRadioButton("AI の攻撃イベントを次の武装に付け替える:");
                ButtonGroup bg = new ButtonGroup(); bg.add(drop); bg.add(re);
                JPanel pnl = Form.vbox();
                pnl.add(Form.note("武装 #" + idx + "「" + wm.path("" + idx).path("weaponName").asText() + "」を削除します。\n後ろの武装の番号は 1 つずつ詰まり、AI 表・WeaponEquip.dat（初期装備）・MaterialBlock は自動で付け替えます。\n"
                        + "この武装を使う AI 攻撃イベント: " + uses + " 件。\n注意: セーブデータの装備画面の配置は番号で保存されている可能性があります（装備し直してください）。"));
                pnl.add(drop); pnl.add(re); pnl.add(repl);
                if (JOptionPane.showConfirmDialog(this, pnl, "武装の削除", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
                r = st.removeWeapon(curMech, idx, re.isSelected() && repl.getSelectedItem() != null ? ((Form.Item) repl.getSelectedItem()).value : -1);
                break;
            }
            default: return;
        }
        structResult(r);
    }

    /** Shows the outcome of a structural operation; offers undo when it introduced new mek_check violations. */
    void structResult(Structure.Result r) {
        if (!r.ok) {
            status(r.message.split("\n")[0]);
            JOptionPane.showMessageDialog(this, r.message, "構造の変更", JOptionPane.WARNING_MESSAGE);
            return;
        }
        status(r.message + (r.log.isEmpty() ? "" : " — " + String.join(" / ", r.log)));
        if (r.selWeapon != null) {
            weaponModel.recompute();
            int row = weaponModel.keys.indexOf(r.selWeapon);
            if (row >= 0) { weaponTable.getSelectionModel().setSelectionInterval(row, row); weaponTable.scrollRectToVisible(weaponTable.getCellRect(row, 0, true)); }
            selectWeapon(r.selWeapon);
        } else if (r.selPtr != null && r.selPtr.startsWith("/skillList")) tree.selectPtr(r.selPtr);
        else if (r.selSkill >= 0) tree.select(r.selSkill, r.selTurn, -1, -1);
        if (!r.newViolations.isEmpty()) {
            JTextArea a = new JTextArea(r.text(), 14, 80);
            a.setEditable(false);
            if (JOptionPane.showConfirmDialog(this, new JScrollPane(a), "mek_check: 新しい違反（このままでは書き出せません）— 元に戻しますか？", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
                undo();
        }
    }

    // ================================================================= v2: SE preview
    void playSelected() {
        Editors.CandRow c = UiFind.first(propHolder, Editors.CandRow.class);
        if (c == null) { status("試聴: ＳＥ・ボイス・攻撃（ヒットＳＥ）のイベントを選んでください"); return; }
        c.playNow();
    }

    // ================================================================= v2: search
    void doSearch(String q) {
        if (p == null || search == null) return;
        if (q.isBlank()) return;
        List<SearchIndex.Hit> hits = search.search(q, 300);
        if (!search.isBuilt()) status("検索の準備中（全 mek / waz を読み込み中）…結果は途中までです");
        if (hits.isEmpty()) { status("見つかりません: " + q); return; }
        JList<SearchIndex.Hit> list = new JList<>(hits.toArray(new SearchIndex.Hit[0]));
        list.setVisibleRowCount(20);
        JDialog d = new JDialog(this, "検索: " + q + "（" + hits.size() + " 件）— ダブルクリック / Enter で開く", false);
        Runnable go = () -> { SearchIndex.Hit h = list.getSelectedValue(); if (h != null) { d.dispose(); openHit(h); } };
        list.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) go.run(); } });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "go");
        list.getActionMap().put("go", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { go.run(); } });
        d.getContentPane().add(new JScrollPane(list));
        d.setSize(720, 460);
        d.setLocationRelativeTo(this);
        list.setSelectedIndex(0);
        d.setVisible(true);
    }

    void openHit(SearchIndex.Hit h) {
        if (h.kind.equals("技")) { openSkill(h.waz, h.skill); return; }
        Project.Mech m = p.mechs.get(h.mechId);
        leftTabs.setSelectedIndex(0);
        if (!mechFilter.getText().isEmpty()) mechFilter.setText("");
        Runnable after = () -> { if (h.weapon >= 0 && curMek != null && curMek.tree.path("mekWeaponInfoMap").has("" + h.weapon)) selectWeapon("" + h.weapon); else { mechTab = 0; showMechPage(); } };
        if (curMech == m && curWazIdx == m.wazIdx && curWaz != null) after.run(); else selectMech(m, after);
    }

    // ================================================================= v2: diff vs original
    void showDiff() {
        if (p == null) return;
        List<FileDoc> docs = p.dirtyDocs();
        if (docs.isEmpty()) { JOptionPane.showMessageDialog(this, "変更されたファイルはありません。"); return; }
        docs.sort(Comparator.comparingInt(d -> d == curWaz ? 0 : d == curMek ? 1 : d.ext.equals("waz") ? 2 : d.ext.equals("mek") ? 3 : 4));
        JComboBox<FileDoc> fc = new JComboBox<>(docs.toArray(new FileDoc[0]));
        JTextArea a = new JTextArea(28, 110);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Runnable fill = () -> {
            FileDoc d = (FileDoc) fc.getSelectedItem();
            if (d == null) return;
            try {
                List<TreeDiff.Line> l = TreeDiff.diff(codec.parseToTree(d.name, d.originalBytes), d.tree, 2000);
                StringBuilder sb = new StringBuilder(d.name + "（元: " + d.sourceLabel + "）と現在の差分 " + l.size() + (l.size() >= 2000 ? "+ 行（先頭 2000 行）" : " 行") + "   ~ 変更 / + 追加 / - 削除\n\n");
                for (TreeDiff.Line x : l) sb.append(x.op).append(' ').append(pretty(d, x.path)).append("\n      ").append(x.text).append('\n');
                a.setText(sb.toString());
                a.setCaretPosition(0);
            } catch (Exception e) { a.setText(e.toString()); }
        };
        fc.addActionListener(e -> fill.run());
        fill.run();
        JPanel pnl = new JPanel(new BorderLayout(4, 4));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("ファイル: ")); top.add(fc);
        pnl.add(top, BorderLayout.NORTH);
        pnl.add(new JScrollPane(a), BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, pnl, "元との差分", JOptionPane.PLAIN_MESSAGE);
    }

    /** "/skillList/12/phasesInfo/0/..." -> "技12「名前」 ターン0 …" for readability. */
    static String pretty(FileDoc d, String path) {
        String[] t = path.split("/");
        StringBuilder sb = new StringBuilder();
        if (t.length > 2 && t[1].equals("skillList")) {
            int si = Integer.parseInt(t[2]);
            sb.append("技").append(si).append("「").append(WazNav.skillName(d.tree, si)).append("」");
            if (t.length > 4) sb.append(" ターン").append(t[4]);
            if (t.length > 6) sb.append(" スロット[").append(d.tree.at("/skillList/" + si + "/phasesInfo/" + t[4] + "/skillUnitCollection/" + t[6] + "/unitQuantity").asText("?")).append("]");
            if (t.length > 8) sb.append(" イベント").append(t[8]);
            sb.append("  ");
        } else if (t.length > 2 && t[1].equals("mekWeaponInfoMap")) {
            sb.append("武装#").append(t[2]).append("「").append(d.tree.at("/mekWeaponInfoMap/" + t[2] + "/weaponName").asText("")).append("」  ");
        } else if (t.length > 2 && t[1].equals("mekAiInfoList")) {
            sb.append("AI[").append(t[2]).append("]").append(t.length > 4 ? " イベント" + t[4] : "").append("  ");
        }
        return sb + path;
    }

    // ================================================================= v2: help
    void showHelp() {
        JEditorPane ep = new JEditorPane("text/html", Help.HTML);
        ep.setEditable(false);
        ep.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(820, 620));
        JDialog d = new JDialog(this, "BsdxWorkshop " + VERSION + " — 使い方", false);
        d.getContentPane().add(sp);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    /** v3: every column as wide as its widest cell / header (no "…" in cells; the table scrolls horizontally). */
    static void fitColumns(JTable t) {
        javax.swing.table.TableColumnModel cm = t.getColumnModel();
        for (int c = 0; c < cm.getColumnCount(); c++) {
            javax.swing.table.TableColumn col = cm.getColumn(c);
            javax.swing.table.TableCellRenderer hr = col.getHeaderRenderer() != null ? col.getHeaderRenderer() : t.getTableHeader().getDefaultRenderer();
            int w = hr.getTableCellRendererComponent(t, col.getHeaderValue(), false, false, -1, c).getPreferredSize().width;
            int rows = Math.min(t.getRowCount(), 3000);
            for (int r = 0; r < rows; r++) {
                Component k = t.prepareRenderer(t.getCellRenderer(r, c), r, c);
                w = Math.max(w, k.getPreferredSize().width);
            }
            col.setPreferredWidth(w + 10);
            col.setWidth(w + 10);
        }
    }

    // ================================================================= table models
    final class MechModel extends AbstractTableModel {
        List<Project.Mech> rows = new ArrayList<>();
        final String[] cols = {"ID", "機体名", "パイロット", "変更"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
        public Object getValueAt(int r, int c) {
            Project.Mech m = rows.get(r);
            switch (c) {
                case 0: return m.id;
                case 1: return m.mekName;
                case 2: return m.pilot;
                default: {
                    if (p == null) return "";
                    FileDoc a = p.docIfLoaded(m.mekFile);
                    String wf = p.wazFile(m.wazIdx);
                    FileDoc b = wf == null ? null : p.docIfLoaded(wf);
                    return (a != null && a.isDirty()) || (b != null && b.isDirty()) ? "●" : "";
                }
            }
        }
    }

    final class SharedModel extends AbstractTableModel {
        List<Integer> rows = new ArrayList<>();
        final String[] cols = {"#", "waz", "名前", "技数", "持ち主"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            int i = rows.get(r);
            JsonNode e = p.wazaList().get(i);
            switch (c) {
                case 0: return i;
                case 1: return e.path("wazaDisplayName").asText();
                case 2: return e.path("wazaName").asText();
                case 3: return e.path("param").asInt();
                default: { Project.Mech m = p.mechOfWaz(i); return m == null ? "共通" : m.mekName; }
            }
        }
    }

    final class WeaponModel extends AbstractTableModel {
        FileDoc mek, waz;
        List<String> keys = new ArrayList<>();
        List<Object[]> data = new ArrayList<>();
        final String[] cols = {"#", "武装名", "区分", "種別", "攻撃Lv", "熱 初/習", "技", "攻撃（威力×ヒット数）"};
        void set(FileDoc mek, FileDoc waz) { this.mek = mek; this.waz = waz; recompute(); }
        void recompute() {
            keys = new ArrayList<>(); data = new ArrayList<>();
            if (mek != null) {
                List<String> ks = new ArrayList<>();
                mek.tree.path("mekWeaponInfoMap").fieldNames().forEachRemaining(ks::add);
                ks.sort(Comparator.comparingInt(k -> { try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 9999; } }));
                for (String k : ks) {
                    JsonNode w = mek.tree.path("mekWeaponInfoMap").path(k);
                    int si = w.path("wazSequence").asInt();
                    int cat = w.path("weaponCategory").asInt();
                    keys.add(k);
                    data.add(new Object[]{k, w.path("weaponName").asText(), cat == 0 ? "通常" : cat == 1 ? "FC" : cat == 2 ? "INIT" : "" + cat,
                            w.path("weaponType").asInt() == 0 ? "格闘" : "射撃", w.path("forceCrashAmount").asInt(),
                            w.path("heatMaxConsumption").asInt() + "/" + w.path("heatMinConsumption").asInt(),
                            si + " " + (waz == null ? "" : WazNav.skillName(waz.tree, si)), waz == null ? "" : EventText.hitsSummary(p, waz.tree, si)});
                }
            }
            int sel = weaponTable.getSelectedRow();
            recomputing = true;
            try {
                fireTableDataChanged();
                fitColumns(weaponTable);
                if (sel >= 0 && sel < data.size()) weaponTable.getSelectionModel().setSelectionInterval(sel, sel);
            } finally { recomputing = false; }
        }
        public int getRowCount() { return data.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) { return data.get(r)[c]; }
    }

    // ================================================================= test hooks
    public Project project() { return p; }
    public SkillTree tree() { return tree; }
    public Timeline timeline() { return timeline; }
    public JTable mechTable() { return mechTable; }
    public JTable weaponTable() { return weaponTable; }
    public boolean isBusy() { return busy; }
    public void selectMechNow(Project.Mech m) throws Exception {
        curMech = m; curMek = p.doc(m.mekFile); curWaz = p.doc(p.wazFile(m.wazIdx)); curWazIdx = m.wazIdx;
        weaponModel.set(curMek, curWaz);
        tree.setWaz(curWaz, curWazIdx, m, "機体 " + m.id + " " + m.mekName + " / " + m.pilot + " — " + m.mekFile + "（元: " + curMek.sourceLabel + "）");
        outline.setMech(m, curMek, curWaz);
        syncMechRow(m);
        curWeaponKey = null;
        mechTab = 0;
        showMechPage();
    }
    public Outline outline() { return outline; }
    public Overview overview() { return overview; }
    public void goBackForTest() { goBack(); }
    public void goForwardForTest() { goForward(); }
    public int historySize() { return history.list.size(); }
    public void showMechPageForTest() { showMechPage(); }
    public void selectWeaponRow(int row) { weaponTable.getSelectionModel().setSelectionInterval(row, row); }
    public void structOnTreeForTest(String kind) { structOnTree(kind); }
    public void weaponActionForTest(String kind) { weaponAction(kind); }
    public void openAiTabForTest() { openAiTab(); }
    public void showDiffForTest() { showDiff(); }
    public void showHelpForTest() { showHelp(); }
    public void searchForTest(String q) { searchField.setText(q); doSearch(q); }
    public JComponent props() { return propHolder; }
    /** Opens the tree context menu on the selected row (as a right click would). */
    public void treePopupForTest() {
        centerTabs.setSelectedIndex(1);
        getContentPane().validate();
        int row = tree.tree.getLeadSelectionRow();
        Rectangle r = tree.tree.getRowBounds(row);
        java.awt.event.MouseEvent e = new java.awt.event.MouseEvent(tree.tree, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, r.x + 20, r.y + r.height / 2, 1, true, java.awt.event.MouseEvent.BUTTON3);
        for (java.awt.event.MouseListener l : tree.tree.getMouseListeners()) l.mousePressed(e);
    }
}
