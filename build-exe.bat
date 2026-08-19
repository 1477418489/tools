@echo off
chcp 65001 >nul
setlocal

set "PROJECT_DIR=%~dp0"
set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
if not defined CONFIGURATION set "CONFIGURATION=Release"
if not defined RUNTIME_ID set "RUNTIME_ID=win-x64"
if not defined OUTPUT_DIR set "OUTPUT_DIR=%PROJECT_DIR%\dist\FxTools"

echo =====================================
echo        FxTools WinUI 3 Builder
echo =====================================
echo [INFO] Configuration: %CONFIGURATION%
echo [INFO] Runtime:       %RUNTIME_ID%
echo [INFO] Output:        %OUTPUT_DIR%
echo.

where dotnet.exe >nul 2>nul
if errorlevel 1 (
    echo [ERROR] dotnet.exe not found. Install the .NET 10 SDK.
    exit /b 1
)

pushd "%PROJECT_DIR%" >nul

echo [INFO] Running Core tests...
dotnet test "tests\FxTools.Core.Tests\FxTools.Core.Tests.csproj" -c "%CONFIGURATION%"
if errorlevel 1 goto :build_failed

if exist "%OUTPUT_DIR%" (
    echo [INFO] Removing previous output...
    rmdir /s /q "%OUTPUT_DIR%"
    if errorlevel 1 goto :build_failed
)

echo [INFO] Publishing self-contained x64 application...
dotnet publish "src\FxTools.App\FxTools.App.csproj" ^
    -c "%CONFIGURATION%" ^
    -r "%RUNTIME_ID%" ^
    --self-contained true ^
    -p:WindowsAppSDKSelfContained=true ^
    -p:PublishSingleFile=false ^
    -o "%OUTPUT_DIR%"
if errorlevel 1 goto :build_failed

if not exist "%OUTPUT_DIR%\FxTools.exe" (
    echo [ERROR] Publish completed without FxTools.exe.
    goto :build_failed
)

set "HASH_TARGET=%OUTPUT_DIR%\FxTools.exe"
set "HASH_VALUE="
for /f "skip=1 delims=" %%H in ('certutil -hashfile "%HASH_TARGET%" SHA256 2^>nul') do if not defined HASH_VALUE set "HASH_VALUE=%%H"
if not defined HASH_VALUE goto :build_failed
set "HASH_VALUE=%HASH_VALUE: =%"
> "%HASH_TARGET%.sha256.txt" echo %HASH_VALUE%

echo.
echo =====================================
echo Build finished.
echo Entry: %OUTPUT_DIR%\FxTools.exe
echo =====================================
popd >nul
endlocal
exit /b 0

:build_failed
set "BUILD_EXIT=%ERRORLEVEL%"
if "%BUILD_EXIT%"=="0" set "BUILD_EXIT=2"
echo [ERROR] Build failed with exit code %BUILD_EXIT%.
popd >nul
endlocal & exit /b %BUILD_EXIT%
