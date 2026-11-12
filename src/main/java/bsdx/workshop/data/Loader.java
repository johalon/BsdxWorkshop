package bsdx.workshop.data;

import bsdx.workshop.vfs.GameFs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds a Project from settings: game folder pacs (read-only) + optional overlay. */
public final class Loader {
    private Loader() {}

    public static Project open(Codec codec, Fields fields, Path gameDir, boolean mountUpdate3to7, Path overlay, List<String> log) throws IOException {
        GameFs fs = new GameFs();
        List<String> l = log == null ? new ArrayList<>() : log;
        if (gameDir != null) {
            if (!Files.isDirectory(gameDir)) throw new IOException("ゲームフォルダがありません: " + gameDir);
            fs.mountGameDir(gameDir, mountUpdate3to7, l);
            if (ExeTerms.load(gameDir)) l.add("用語: BaldrSky.exe の項目名と照合します（exe に無い名前には * を付けます）");
            else l.add("用語: BaldrSky.exe が見つからないため、ゲームの用語かどうかの印は付けません");
        }
        if (overlay != null) {
            if (!Files.exists(overlay)) throw new IOException("オーバーレイが見つかりません: " + overlay);
            fs.setOverlay(GameFs.openLayer(overlay, "オーバーレイ:" + overlay.getFileName()));
            l.add("overlay " + overlay);
        }
        if (fs.layers().isEmpty()) throw new IOException("pac が 1 つも見つかりません: " + gameDir);
        Project p = new Project(codec, fields, fs);
        p.loadRegistries();
        p.log.addAll(l);
        return p;
    }
}
