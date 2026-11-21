@echo off
title Build Automation - MalikaDrop
color 0A

echo ========================================================
echo             BUILDING MALIKA DROP (.EXE)
echo ========================================================
echo.

:: 1. Masuk ke folder direktori tempat file .bat berada
cd /d "%~dp0"

:: 2. Bersihkan cache build dan spec lama
echo [1/2] Membersihkan cache lama...
if exist "build" rd /s /q "build"
if exist "MalikaDrop.spec" del /f /q "MalikaDrop.spec"
echo Done.
echo.

:: 3. Build Malika Drop
echo [2/2] Memproses Build MalikaDrop.exe (dengan --clean)...
pyinstaller --clean --noconsole --onefile ^
    --hidden-import=qrcode ^
    --name "MalikaDrop" ^
    --icon "logo.ico" ^
    --add-data "logo.png;." ^
    --add-data "logo.ico;." ^
    --add-data "cloudflared.exe;." ^
    "MalikaDrop.py"

if %ERRORLEVEL% NEQ 0 (
    color 0C
    echo ERROR: Gagal melakukan build MalikaDrop!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo      SUCCESS: MalikaDrop.exe berhasil dibuat di \dist!
echo ========================================================
pause