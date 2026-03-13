@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  用途: 一键打包 Windows EXE 安装程序（jpackage --type exe）
REM  前置: 先执行 `mvn clean javafx:jlink` 生成 runtime image
REM ============================================================

REM ==== 可配置参数（按需修改）====
set "APP_NAME=FxTools"
set "APP_VERSION=1.0.0"
set "VENDOR=JavaFxTools"
set "MAIN_MODULE=plugin.javafxtools"
set "MAIN_CLASS=plugin.javafxtools.ToolsApplication"
set "RUNTIME_IMAGE=target\app"
set "ICON_PATH=src\main\resources\favicon.ico"
set "DEST_DIR=dist"
set "WIN_MENU=true"
set "WIN_SHORTCUT=true"
set "CONSOLE=false"

REM ==== 自动定位 jpackage（优先 JAVA_HOME）====
set "JPACKAGE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jpackage.exe" set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
)
if not defined JPACKAGE (
    where jpackage >nul 2>nul
    if not errorlevel 1 set "JPACKAGE=jpackage"
)

if not defined JPACKAGE (
    echo [ERROR] 未找到 jpackage，请安装 JDK 14+ 并配置 JAVA_HOME 或 PATH。
    pause
    exit /b 1
)

if not exist "%RUNTIME_IMAGE%" (
    echo [ERROR] 运行时镜像目录不存在：%RUNTIME_IMAGE%
    echo [HINT] 请先执行: mvn clean javafx:jlink
    pause
    exit /b 2
)

if not exist "%ICON_PATH%" (
    echo [WARN] 图标不存在，将使用默认图标：%ICON_PATH%
    set "ICON_ARG="
) else (
    set "ICON_ARG=--icon \"%ICON_PATH%\""
)

if not exist "%DEST_DIR%" mkdir "%DEST_DIR%"

echo [INFO] 使用 jpackage: %JPACKAGE%
echo [INFO] 开始生成 EXE 安装包...

set "CMD=%JPACKAGE% --type exe --name \"%APP_NAME%\" --app-version \"%APP_VERSION%\" --vendor \"%VENDOR%\" --runtime-image \"%RUNTIME_IMAGE%\" --module %MAIN_MODULE%/%MAIN_CLASS% --dest \"%DEST_DIR%\""

if /I "%WIN_MENU%"=="true" set "CMD=!CMD! --win-menu"
if /I "%WIN_SHORTCUT%"=="true" set "CMD=!CMD! --win-shortcut"
if /I "%CONSOLE%"=="true" set "CMD=!CMD! --win-console"
if defined ICON_ARG set "CMD=!CMD! !ICON_ARG!"

call !CMD!
if errorlevel 1 (
    echo [ERROR] EXE 安装包打包失败。
    pause
    exit /b 3
)

echo [INFO] 打包完成，输出目录：%DEST_DIR%
for %%f in ("%DEST_DIR%\*.exe") do (
    echo [INFO] 生成文件：%%~nxf
)

pause
endlocal
