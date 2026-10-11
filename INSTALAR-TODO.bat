@echo off
rem Doble clic para instalar todo el entorno (se pide permiso de administrador solo).
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Pidiendo permisos de administrador...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar-todo.ps1"
echo.
echo Terminado. Revisa el RESUMEN de arriba y luego REINICIA la PC.
pause
