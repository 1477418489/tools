@echo off
setlocal enabledelayedexpansion

echo =====================================
echo        JavaFX JPackage Builder
echo =====================================

REM ==== config ====
set JPACKAGE_PATH=D:\tools\jdk\jdk-23.0.2\bin\jpackage.exe
set APP_NAME=FxTools
set MAIN_MODULE=plugin.javafxtools
set MAIN_CLASS=plugin.javafxtools.ToolsApplication
set RUNTIME_IMAGE=D:\github\tools\target\app
set ICON_PATH=D:\github\tools\target\classes\favicon.ico
set OUTPUT_DIR=dist

REM ==== check jpackage ====
if not exist "%JPACKAGE_PATH%" (
 echo [ERROR] jpackage not found:
 echo %JPACKAGE_PATH%
 pause
 exit /b 1
)

REM ==== check runtime ====
if not exist "%RUNTIME_IMAGE%" (
 echo [ERROR] runtime-image not found:
 echo %RUNTIME_IMAGE%
 echo Run: mvn javafx:jlink
 pause
 exit /b 1
)

REM ==== create output dir ====
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM ==== detect WiX ====
set PACKAGE_TYPE=app-image

where candle.exe >nul 2>nul
if not errorlevel 1 (
 where light.exe >nul 2>nul
 if not errorlevel 1 (
  set PACKAGE_TYPE=exe
 )
)

echo [INFO] package type: %PACKAGE_TYPE%

REM ==== build command ====
set CMD="%JPACKAGE_PATH%" ^
 --name "%APP_NAME%" ^
 --type %PACKAGE_TYPE% ^
 -m "%MAIN_MODULE%/%MAIN_CLASS%" ^
 --runtime-image "%RUNTIME_IMAGE%" ^
 --dest "%OUTPUT_DIR%"

if exist "%ICON_PATH%" (
 set CMD=!CMD! --icon "%ICON_PATH%"
)

if "%PACKAGE_TYPE%"=="exe" (
 set CMD=!CMD! ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut
)

echo.
echo [INFO] running jpackage...
echo.

%CMD%

if errorlevel 1 (
 echo [ERROR] jpackage failed
 pause
 exit /b 2
)

REM ==== MD5 ====
set OUT_EXE=%OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe

if exist "%OUT_EXE%" (
 for /f "skip=1 tokens=1" %%i in ('certutil -hashfile "%OUT_EXE%" MD5') do (
  echo %%i > "%OUT_EXE%.md5.txt"
  echo [INFO] MD5 saved: %OUT_EXE%.md5.txt
  goto done
 )
)

echo [WARN] exe not found for MD5

:done

echo.
echo =====================================
echo Build finished
echo Output: %OUTPUT_DIR%
echo =====================================

pause
endlocal