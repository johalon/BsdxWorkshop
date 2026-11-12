@echo off
rem BsdxWorkshop (bundled Java runtime)
cd /d "%~dp0"
start "" "%~dp0runtime\bin\javaw.exe" -Xmx2g -Dfile.encoding=UTF-8 -Dsun.java2d.d3d=false -jar "%~dp0app\BsdxWorkshop.jar" %*
