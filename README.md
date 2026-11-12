# BsdxWorkshop

BSDX の機体・技データ（mek / waz など）を見て、調整して、MOD の `Update3.pac` として書き出す Windows 用ツールです。

- 機体 → 武装 → 技 → 弾・エフェクトのつながりを一覧で追える
- 技のタイムライン（ターン・スロット・イベント）を 1 フレーム単位で表示・編集
- 書き出し前の検証、変更点の一覧、元に戻す・やり直し

ファンメイドの非公式ツールです。ゲームフォルダは読み取り専用で開き、書き込みません。

## ダウンロードと起動

[Releases](../../releases) から `BsdxWorkshop-<版>-win64.zip` を取って展開し、`BsdxWorkshop.bat` を実行します。Java は同梱しているので、追加のインストールは要りません。

使い方はツール内の「ヘルプ → 使い方」（F1）にあります。項目名に付いた `*` は、ゲームの exe には無い、このツールで付けた名前です。

## ビルド

JDK 17 と Maven が要ります。

```
git clone --recursive https://github.com/johalon/BsdxWorkshop.git
cd BsdxWorkshop
mvn -f nexas_dx/pom.xml -Dmaven.test.skip=true compile jar:jar install:install   # データ層（NeXAS_DX のフォーク）
mvn package                                              # target/BsdxWorkshop.jar
powershell -ExecutionPolicy Bypass -File release\make_release.ps1   # Java 同梱の zip（Windows）
```

`v*` のタグを push すると、GitHub Actions が同じ手順で zip を作り、Release の下書きに添付します。

`src/test/java` の `SelfTest` などはゲームのファイルを使う確認用のプログラムです（JUnit ではありません）。

## 構成

| パス | 内容 |
|---|---|
| `src/main/java/bsdx/workshop` | ツール本体（Swing + FlatLaf） |
| `nexas_dx/` | [NeXAS_DX](https://github.com/Karaik/NeXAS_DX) のフォーク（[johalon/NeXAS_DX](https://github.com/johalon/NeXAS_DX)）（git submodule）。`bsdxworkshop` ブランチに、このツールで要った修正をコミットしています |
| `release/` | 配布 zip の起動用 bat・説明・ライセンス、zip を作るスクリプト |

## 謝辞

データの読み書き（pac / waz / mek などの解析）は、[Karaik](https://github.com/Karaik) さんの [NeXAS_DX](https://github.com/Karaik/NeXAS_DX)（MIT License）を元にしています。この部分の解析は NeXAS_DX の成果です。

## 注意

- このプロジェクトには、ゲームのリソースは含まれていません。
- 利用は、各自の責任のもとで行ってください。

## ライセンス

このツールのコードは MIT License です（[LICENSE](LICENSE)）。同梱しているライブラリと Java 実行環境のライセンスは [release/licenses/THIRD_PARTY.txt](release/licenses/THIRD_PARTY.txt) にあります。

