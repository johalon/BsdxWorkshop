# BsdxWorkshop: Java 同梱の配布 zip を作る（Windows / JDK 17 の jlink を使用）
#   mvn -f nexas_dx\pom.xml -Dmaven.test.skip=true compile jar:jar install:install ; mvn package   （先に本体の jar を作る）
#   powershell -ExecutionPolicy Bypass -File release\make_release.ps1 [-Version 5.0.0]
# 出力: release\out\BsdxWorkshop-<ver>-win64.zip（GitHub Actions も同じスクリプトを使います）
param([string]$Version = "5.0.0", [string]$Jar = "")
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
if ($Jar -eq "") { $Jar = Join-Path $root "target\BsdxWorkshop.jar" }
$out = Join-Path $here "out"
$stage = Join-Path $out "BsdxWorkshop"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path "$stage\app" | Out-Null
# 1) 最小の Java 実行環境（jdeps の結果 + 文字コード MS932 用の jdk.charsets など）
$mods = "java.base,java.compiler,java.desktop,java.management,java.naming,java.sql,java.scripting,java.prefs,jdk.unsupported,jdk.charsets,jdk.zipfs"
# jlink: JAVA_HOME か、PATH の java の java.home から探す
$jlink = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jlink.exe")) { $jlink = "$env:JAVA_HOME\bin\jlink.exe" }
if (-not $jlink) {
    $jh = (cmd /c "java -XshowSettings:properties -version 2>&1" | Select-String 'java.home' | Select-Object -First 1).ToString().Split('=')[1].Trim()
    if (Test-Path "$jh\bin\jlink.exe") { $jlink = "$jh\bin\jlink.exe" }
}
if (-not $jlink) { throw "jlink が見つかりません（JDK 17 を入れて JAVA_HOME を設定してください）" }
& $jlink --add-modules $mods --strip-debug --no-man-pages --no-header-files --compress=2 --output "$stage\runtime"
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
# 2) 本体と起動用 bat、説明・ライセンス
Copy-Item $Jar "$stage\app\BsdxWorkshop.jar"
Copy-Item (Join-Path $here "BsdxWorkshop.bat") $stage
Copy-Item (Join-Path $here "README.txt") $stage
Copy-Item -Recurse (Join-Path $here "licenses") "$stage\licenses"
Copy-Item (Join-Path $root "LICENSE") "$stage\licenses\BsdxWorkshop-LICENSE.txt"
# 3) zip
$zip = Join-Path $out ("BsdxWorkshop-" + $Version + "-win64.zip")
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $stage -DestinationPath $zip
Get-Item $zip | Select-Object Name, Length
