@echo off
setlocal EnableDelayedExpansion

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot

if "%ANDROID_HOME%"=="" (
    set ANDROID_HOME=C:\Android\android-sdk
)

cd /d "%~dp0"

set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"

echo === Verificando dispositivo conectado (USB o emulador) ===
"%ADB%" devices

set DEVICE_FOUND=
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" set DEVICE_FOUND=1
)

if not defined DEVICE_FOUND (
    echo.
    echo No hay nada por USB ni emulador. Probando conectar por WiFi...
    if exist "wifi_device.txt" (
        set /p WIFI_ADDR=<wifi_device.txt
        echo Conectando a !WIFI_ADDR! por WiFi...
        "%ADB%" connect !WIFI_ADDR!
        "%ADB%" devices
        for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
            if "%%b"=="device" set DEVICE_FOUND=1
        )
    ) else (
        echo No existe wifi_device.txt con la IP:puerto del celular.
        echo Crea un archivo wifi_device.txt en la raiz del proyecto con una sola linea, por ejemplo:
        echo   192.168.1.6:40000
        echo Esa IP:puerto se ve en el celular, en Opciones de desarrollador ^> Depuracion inalambrica
        echo ^(la pantalla principal, no el dialogo de emparejamiento^). Cambia cada vez que se apaga
        echo y prende la depuracion inalambrica, asi que hay que actualizar el archivo si deja de andar.
    )
)

if not defined DEVICE_FOUND (
    echo.
    echo No se encontro ningun dispositivo ^(USB, emulador ni WiFi^). Cancelando.
    exit /b 1
)

echo.
echo === Compilando e instalando ===
call gradlew.bat installDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Listo. La app se instalo.
) else (
    echo.
    echo Algo fallo. Revisa el mensaje de error arriba.
)

endlocal
