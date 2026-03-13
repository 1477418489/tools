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
set "JPACKAGE_PATH=D:\tools\jdk\jdk-23.0.2\bin\jpackage.exe"

REM ==== 固定 jpackage 路径（多 JDK 环境建议显式指定）====
if not exist "%JPACKAGE_PATH%" (
    echo [ERROR] jpackage 不存在：%JPACKAGE_PATH%
    echo [HINT] 请确认 JDK 路径，或修改 build-exe.bat 中 JPACKAGE_PATH。
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

echo [INFO] 使用 jpackage: %JPACKAGE_PATH%
echo [INFO] 开始生成 EXE 安装包...

set "EXTRA_ARGS="
if /I "%WIN_MENU%"=="true" set "EXTRA_ARGS=!EXTRA_ARGS! --win-menu"
if /I "%WIN_SHORTCUT%"=="true" set "EXTRA_ARGS=!EXTRA_ARGS! --win-shortcut"
if /I "%CONSOLE%"=="true" set "EXTRA_ARGS=!EXTRA_ARGS! --win-console"
if defined ICON_ARG set "EXTRA_ARGS=!EXTRA_ARGS! !ICON_ARG!"

call "%JPACKAGE_PATH%" ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --vendor "%VENDOR%" ^
  --runtime-image "%RUNTIME_IMAGE%" ^
  --module %MAIN_MODULE%/%MAIN_CLASS% ^
  --dest "%DEST_DIR%" ^
  !EXTRA_ARGS!
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
