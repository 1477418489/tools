@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem Single Windows packaging entry: build a jlink runtime, then package it with jpackage.
set "PROJECT_DIR=%~dp0"
set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

if not defined APP_NAME set "APP_NAME=FxTools"
if not defined APP_VERSION set "APP_VERSION=1.0.0"
if not defined MAIN_MODULE set "MAIN_MODULE=plugin.javafxtools"
if not defined MAIN_CLASS set "MAIN_CLASS=plugin.javafxtools.ToolsApplication"
if not defined RUNTIME_IMAGE set "RUNTIME_IMAGE=%PROJECT_DIR%\target\app"
if not defined ICON_PATH set "ICON_PATH=%PROJECT_DIR%\target\classes\favicon.ico"
if not defined OUTPUT_DIR set "OUTPUT_DIR=%PROJECT_DIR%\dist"
if not defined PACKAGE_TYPE set "PACKAGE_TYPE=app-image"

set "APP_IMAGE_DIR=%OUTPUT_DIR%\%APP_NAME%"

echo =====================================
echo        JavaFX Tools Packager
echo =====================================
echo.

set "PACKAGING_JDK="

if defined PACKAGING_JAVA_HOME (
    if exist "%PACKAGING_JAVA_HOME%\bin\jpackage.exe" (
        set "PACKAGING_JDK=%PACKAGING_JAVA_HOME%"
    ) else (
        echo [ERROR] PACKAGING_JAVA_HOME does not provide jpackage.exe: %PACKAGING_JAVA_HOME%
        exit /b 1
    )
)

if not defined PACKAGING_JDK if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jpackage.exe" (
        set "PACKAGING_JDK=%JAVA_HOME%"
    ) else (
        echo [WARN] Ignoring JAVA_HOME without jpackage.exe: %JAVA_HOME%
    )
)

if not defined PACKAGING_JDK (
    for /d %%J in ("D:\tools\jdk\jdk-23*" "D:\tools\jdk\jdk-24*" "D:\tools\jdk\jdk-25*" "%ProgramFiles%\Java\jdk-23*" "%ProgramFiles%\Java\jdk-24*" "%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-23*" "%ProgramFiles%\Eclipse Adoptium\jdk-24*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*") do (
        if not defined PACKAGING_JDK if exist "%%~fJ\bin\jpackage.exe" set "PACKAGING_JDK=%%~fJ"
    )
)

if not defined PACKAGING_JDK (
    for /f "delims=" %%J in ('where jpackage.exe 2^>nul') do (
        if not defined PACKAGING_JDK (
            for %%D in ("%%~dpJ..") do set "PACKAGING_JDK=%%~fD"
        )
    )
)

if not defined PACKAGING_JDK (
    echo [ERROR] jpackage.exe not found. Set PACKAGING_JAVA_HOME or JAVA_HOME to a full JDK 23+.
    if defined JAVA_HOME echo [ERROR] Current JAVA_HOME=%JAVA_HOME%
    exit /b 1
)

set "JAVA_HOME=%PACKAGING_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if exist "%PROJECT_DIR%\mvnw.cmd" (
    set "MAVEN_CMD=%PROJECT_DIR%\mvnw.cmd"
) else (
    for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do (
        if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
    )
)

if not defined MAVEN_CMD (
    echo [ERROR] Maven not found. Install Maven or add mvn.cmd to PATH.
    exit /b 1
)

if not exist "%OUTPUT_DIR%" (
    mkdir "%OUTPUT_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to create output directory: %OUTPUT_DIR%
        exit /b 1
    )
)

echo [INFO] Project: %PROJECT_DIR%
echo [INFO] JDK: %JAVA_HOME%
echo [INFO] Maven: %MAVEN_CMD%
echo [INFO] Output: %OUTPUT_DIR%
echo.

pushd "%PROJECT_DIR%" >nul
echo [INFO] Building jlink runtime...
call "%MAVEN_CMD%" -q clean javafx:jlink
set "BUILD_EXIT=%ERRORLEVEL%"
popd >nul

if not "%BUILD_EXIT%"=="0" (
    echo [ERROR] Maven jlink build failed.
    exit /b %BUILD_EXIT%
)

if not exist "%RUNTIME_IMAGE%" (
    echo [ERROR] Runtime image not found: %RUNTIME_IMAGE%
    exit /b 2
)

if /i "%PACKAGE_TYPE%"=="auto" (
    set "PACKAGE_TYPE=app-image"
    where candle.exe >nul 2>nul
    if not errorlevel 1 (
        where light.exe >nul 2>nul
        if not errorlevel 1 set "PACKAGE_TYPE=exe"
    )
)

if exist "%APP_IMAGE_DIR%" (
    echo [INFO] Removing old app image: %APP_IMAGE_DIR%
    rmdir /s /q "%APP_IMAGE_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to remove old app image: %APP_IMAGE_DIR%
        exit /b 3
    )
)

for %%F in ("%OUTPUT_DIR%\%APP_NAME%.exe" "%OUTPUT_DIR%\%APP_NAME%-*.exe" "%OUTPUT_DIR%\%APP_NAME%.msi" "%OUTPUT_DIR%\%APP_NAME%-*.msi") do (
    if exist "%%~fF" del /f /q "%%~fF"
)

for %%F in ("%OUTPUT_DIR%\%APP_NAME%.exe.md5.txt" "%OUTPUT_DIR%\%APP_NAME%-*.exe.md5.txt" "%OUTPUT_DIR%\%APP_NAME%.msi.md5.txt" "%OUTPUT_DIR%\%APP_NAME%-*.msi.md5.txt") do (
    if exist "%%~fF" del /f /q "%%~fF"
)

echo [INFO] Package type: %PACKAGE_TYPE%
if not exist "%ICON_PATH%" (
    echo [WARN] Icon not found: %ICON_PATH%
)

echo.
echo [INFO] Running jpackage...
echo.

if exist "%ICON_PATH%" (
    if /i "%PACKAGE_TYPE%"=="exe" (
        jpackage.exe --name "%APP_NAME%" --app-version "%APP_VERSION%" --type "%PACKAGE_TYPE%" -m "%MAIN_MODULE%/%MAIN_CLASS%" --runtime-image "%RUNTIME_IMAGE%" --dest "%OUTPUT_DIR%" --icon "%ICON_PATH%" --win-dir-chooser --win-menu --win-shortcut
    ) else (
        jpackage.exe --name "%APP_NAME%" --app-version "%APP_VERSION%" --type "%PACKAGE_TYPE%" -m "%MAIN_MODULE%/%MAIN_CLASS%" --runtime-image "%RUNTIME_IMAGE%" --dest "%OUTPUT_DIR%" --icon "%ICON_PATH%"
    )
) else (
    if /i "%PACKAGE_TYPE%"=="exe" (
        jpackage.exe --name "%APP_NAME%" --app-version "%APP_VERSION%" --type "%PACKAGE_TYPE%" -m "%MAIN_MODULE%/%MAIN_CLASS%" --runtime-image "%RUNTIME_IMAGE%" --dest "%OUTPUT_DIR%" --win-dir-chooser --win-menu --win-shortcut
    ) else (
        jpackage.exe --name "%APP_NAME%" --app-version "%APP_VERSION%" --type "%PACKAGE_TYPE%" -m "%MAIN_MODULE%/%MAIN_CLASS%" --runtime-image "%RUNTIME_IMAGE%" --dest "%OUTPUT_DIR%"
    )
)

if errorlevel 1 (
    echo [ERROR] jpackage failed.
    exit /b 4
)

set "HASH_TARGET="
if exist "%APP_IMAGE_DIR%\%APP_NAME%.exe" set "HASH_TARGET=%APP_IMAGE_DIR%\%APP_NAME%.exe"

if not defined HASH_TARGET (
    for %%F in ("%OUTPUT_DIR%\%APP_NAME%.exe" "%OUTPUT_DIR%\%APP_NAME%-*.exe" "%OUTPUT_DIR%\%APP_NAME%.msi" "%OUTPUT_DIR%\%APP_NAME%-*.msi") do (
        if not defined HASH_TARGET (
            if exist "%%~fF" set "HASH_TARGET=%%~fF"
        )
    )
)

if defined HASH_TARGET (
    for /f "skip=1 tokens=1" %%H in ('certutil -hashfile "!HASH_TARGET!" MD5') do (
        echo %%H>"!HASH_TARGET!.md5.txt"
        echo [INFO] MD5 saved: !HASH_TARGET!.md5.txt
        goto after_md5
    )
) else (
    echo [WARN] No package file found for MD5 generation.
)

:after_md5
echo.
echo =====================================
echo Build finished.
echo Output: %OUTPUT_DIR%
echo =====================================
endlocal
