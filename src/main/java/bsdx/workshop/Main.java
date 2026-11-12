package bsdx.workshop;

import bsdx.workshop.data.Codec;
import bsdx.workshop.data.Fields;
import bsdx.workshop.data.Settings;
import bsdx.workshop.ui.MainFrame;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point: java -jar BsdxWorkshop.jar [--game DIR] [--overlay PATH|DIR] */
public class Main {
    /** v5: FlatLaf (flat, modern look; pure Java, light). Falls back to the system look if it cannot be set. */
    public static void installLookAndFeel() {
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("Component.arc", 6);
            UIManager.put("Button.arc", 6);
            UIManager.put("TextComponent.arc", 4);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("Tree.rowHeight", 22);
            UIManager.put("Table.rowHeight", 22);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("SplitPane.dividerSize", 6);
        } catch (Throwable t) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("BsdxWorkshop は GUI ツールです（ヘッドレス環境では起動できません）。テストは bsdx.workshop.SelfTest を使ってください。");
            System.exit(2);
        }
        Settings s = Settings.load(Settings.defaultFile());
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--game")) s.gameDir = args[++i];
            else if (args[i].equals("--overlay")) s.overlay = args[++i];
        }
        Codec codec = new Codec();
        Fields fields = Fields.load(codec.mapper);
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel();
            ToolTipManager.sharedInstance().setDismissDelay(20000);
            MainFrame f = new MainFrame(s, codec, fields);
            f.setVisible(true);
            if (s.gameDir != null && !s.gameDir.isBlank() && Files.isDirectory(Path.of(s.gameDir))) f.openProject();
            else f.status("ゲームフォルダを指定してください（ファイル → ゲームフォルダを開く）。" + (s.gameDir == null || s.gameDir.isBlank() ? "" : "見つかりません: " + s.gameDir));
        });
    }
}
