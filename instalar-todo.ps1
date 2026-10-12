# =====================================================================
# instalar-todo.ps1  -  Reinstala el entorno de desarrollo despues de resetear Windows
#
# COMO USARLO (en la PC nueva, con internet):
#   1. Abre PowerShell COMO ADMINISTRADOR.
#   2. cd C:\Users\HP\Desktop\proyectos      (o donde hayas copiado la carpeta)
#   3. powershell -ExecutionPolicy Bypass -File .\instalar-todo.ps1
#      Solo programas de desarrollo (sin Photoshop, FL Studio, etc.):
#      powershell -ExecutionPolicy Bypass -File .\instalar-todo.ps1 -SoloDesarrollo
#
# Al final muestra una lista de lo que fallo, para instalarlo a mano.
# Basado en el inventario de la PC del 11/10/2026.
# =====================================================================
param([switch]$SoloDesarrollo)

$esAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $esAdmin) {
    Write-Host "Ejecuta este script como Administrador." -ForegroundColor Red
    exit 1
}
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Host "No se encontro winget. Instala 'App Installer' desde Microsoft Store y vuelve a intentar." -ForegroundColor Red
    exit 1
}

$fallos = New-Object System.Collections.Generic.List[string]

# Prueba cada ID en orden hasta que uno instale (o ya este instalado).
function Instalar([string]$nombre, [string[]]$ids, [string]$override = '') {
    foreach ($id in $ids) {
        Write-Host ">> $nombre  ($id)" -ForegroundColor Cyan
        $wargs = @('install', '--id', $id, '-e', '--silent', '--accept-package-agreements', '--accept-source-agreements')
        if ($override) { $wargs += @('--override', $override) }
        & winget @wargs
        # 0 = ok, -1978335189 = ya instalado / sin actualizacion aplicable
        if ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq -1978335189) { return }
    }
    $fallos.Add($nombre)
}

function Refrescar-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}

# ---------------------------------------------------------------------
# 1. Programas de desarrollo
# ---------------------------------------------------------------------
Write-Host "`n=== 1/6 Programas de desarrollo ===" -ForegroundColor Yellow
Instalar '.NET SDK 10'            @('Microsoft.DotNet.SDK.10')
Instalar 'JDK 17 (Temurin)'       @('EclipseAdoptium.Temurin.17.JDK')
Instalar 'JDK 21 (Temurin)'       @('EclipseAdoptium.Temurin.21.JDK')
Instalar 'Git'                    @('Git.Git')
Instalar 'VS Code'                @('Microsoft.VisualStudioCode')
Instalar 'Node.js (LTS)'          @('OpenJS.NodeJS.LTS')
Instalar 'Python 3.13'            @('Python.Python.3.13')
Instalar 'CMake'                  @('Kitware.CMake')
Instalar 'LLVM'                   @('LLVM.LLVM')
Instalar 'MSYS2'                  @('MSYS2.MSYS2')
Instalar 'Rustup'                 @('Rustlang.Rustup')
Instalar 'PHP 8.3'                @('PHP.PHP.8.3')
Instalar 'XAMPP'                  @('ApacheFriends.Xampp.8.2', 'ApacheFriends.Xampp.8.0')
Instalar 'Visual Studio Build Tools (C++)' @('Microsoft.VisualStudio.BuildTools', 'Microsoft.VisualStudio.2022.BuildTools') '--passive --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended'
Instalar 'Blender'                @('BlenderFoundation.Blender')
Instalar 'Unity Hub'              @('Unity.UnityHub')
Instalar 'VirtualBox'             @('Oracle.VirtualBox')

# ---------------------------------------------------------------------
# 2. Programas de uso diario / creativos (se saltan con -SoloDesarrollo)
# ---------------------------------------------------------------------
if (-not $SoloDesarrollo) {
    Write-Host "`n=== 2/6 Programas extra ===" -ForegroundColor Yellow
    Instalar 'Brave'              @('Brave.Brave')
    Instalar 'Firefox'            @('Mozilla.Firefox')
    Instalar 'VLC'                @('VideoLAN.VLC')
    Instalar 'OBS Studio'         @('OBSProject.OBSStudio')
    Instalar 'Audacity'           @('Audacity.Audacity')
    Instalar 'WinRAR'             @('RARLab.WinRAR')
    Instalar 'Zoom'               @('Zoom.Zoom')
    Instalar 'CapCut'             @('ByteDance.CapCut')
    Instalar 'Adobe Creative Cloud (para Photoshop)' @('Adobe.CreativeCloud')
    Instalar 'FL Studio'          @('ImageLine.FLStudio')
    Instalar 'Intel Driver and Support Assistant' @('Intel.IntelDriverAndSupportAssistant')
} else {
    Write-Host "`n=== 2/6 Programas extra: OMITIDO (-SoloDesarrollo) ===" -ForegroundColor DarkGray
}

Refrescar-Path

# ---------------------------------------------------------------------
# 3. Variables de entorno y PATH
# ---------------------------------------------------------------------
Write-Host "`n=== 3/6 Variables de entorno ===" -ForegroundColor Yellow
$jdk17 = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
$jdk21 = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk21) { [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk21.FullName, 'User'); Write-Host "JAVA_HOME = $($jdk21.FullName)" }
[Environment]::SetEnvironmentVariable('ANDROID_HOME', 'C:\Android\android-sdk', 'User')
Write-Host "ANDROID_HOME = C:\Android\android-sdk"

$ptools = 'C:\Android\android-sdk\platform-tools'
$pathUsuario = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($pathUsuario -notlike "*$ptools*") {
    [Environment]::SetEnvironmentVariable('Path', ($pathUsuario.TrimEnd(';') + ';' + $ptools), 'User')
    Write-Host "platform-tools agregado al PATH"
}
Refrescar-Path

# ---------------------------------------------------------------------
# 4. Android SDK (C:\Android\android-sdk)  - sin Android Studio, solo SDK
# ---------------------------------------------------------------------
Write-Host "`n=== 4/6 Android SDK ===" -ForegroundColor Yellow
$sdk = 'C:\Android\android-sdk'
$cmdLatest = "$sdk\cmdline-tools\latest"
$sdkmanager = "$cmdLatest\bin\sdkmanager.bat"
try {
    if (-not (Test-Path $sdkmanager)) {
        # Si este enlace deja de funcionar, baja "Command line tools only" desde
        # https://developer.android.com/studio#command-line-tools-only y
        # descomprimelo para que quede: C:\Android\android-sdk\cmdline-tools\latest\bin
        $zip = "$env:TEMP\cmdline-tools.zip"
        $tmp = "$env:TEMP\cmdline-tools-tmp"
        Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip $tmp -Force
        New-Item -ItemType Directory "$sdk\cmdline-tools" -Force | Out-Null
        Move-Item "$tmp\cmdline-tools" $cmdLatest -Force
    }
    # sdkmanager necesita Java
    if ($jdk17) { $env:JAVA_HOME = $jdk17.FullName } elseif ($jdk21) { $env:JAVA_HOME = $jdk21.FullName }

    $paquetes = @(
        'platform-tools',
        'platforms;android-34', 'platforms;android-36',
        'build-tools;34.0.0', 'build-tools;35.0.0',
        'emulator',
        'ndk;27.0.12077973', 'ndk;29.0.14206865',
        'cmake;3.22.1', 'cmake;4.0.2',
        'system-images;android-34;google_apis;x86_64'
    )
    1..40 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$sdk" --licenses | Out-Null
    & $sdkmanager "--sdk_root=$sdk" @paquetes
    if ($LASTEXITCODE -ne 0) { $fallos.Add('Android SDK (algun paquete de sdkmanager fallo)') }
} catch {
    Write-Host "Error con el Android SDK: $($_.Exception.Message)" -ForegroundColor Red
    $fallos.Add('Android SDK')
}

# ---------------------------------------------------------------------
# 5. Python, npm y extensiones de VS Code
# ---------------------------------------------------------------------
Write-Host "`n=== 5/6 Python / npm / VS Code ===" -ForegroundColor Yellow
try {
    py -3.13 -m pip install --upgrade pip
    py -3.13 -m pip install PySide6 pillow pyinstaller mcp yt-dlp requests pywin32 static-ffmpeg imageio-ffmpeg twine python-dotenv uvicorn
    # Servidor MCP propio (si respaldaste Documents\MCP)
    $mcpServer = 'C:\Users\HP\Documents\MCP\vscode-mcp\server'
    if (Test-Path $mcpServer) { py -3.13 -m pip install -e $mcpServer }
    else { Write-Host "No existe $mcpServer : restaura Documents\MCP y vuelve a ejecutar: py -3.13 -m pip install -e <esa ruta>" -ForegroundColor DarkYellow }
} catch { $fallos.Add('Paquetes de Python') }

try {
    npm install -g '@vscode/vsce' 'rea-agents@6.4.0'
    if ($LASTEXITCODE -ne 0) { $fallos.Add('npm global (vsce / rea-agents)') }
} catch { $fallos.Add('npm global') }

$code = (Get-Command code -ErrorAction SilentlyContinue)
if ($code) { $code = $code.Source } else { $code = "$env:LOCALAPPDATA\Programs\Microsoft VS Code\bin\code.cmd" }
if (Test-Path $code) {
    $ext = @(
        'chadalen.vscode-jetbrains-icon-theme',
        'monokai.theme-monokai-pro-vscode',
        'ms-python.debugpy', 'ms-python.python', 'ms-python.vscode-pylance', 'ms-python.vscode-python-envs',
        'redhat.java',
        'vscjava.vscode-gradle', 'vscjava.vscode-java-debug', 'vscjava.vscode-java-dependency',
        'vscjava.vscode-java-pack', 'vscjava.vscode-java-test', 'vscjava.vscode-maven'
    )
    foreach ($e in $ext) { & $code --install-extension $e --force | Out-Null; Write-Host "extension: $e" }
    # Extension propia (puente MCP de VS Code): se instala desde su .vsix
    $vsix = Get-ChildItem 'C:\Users\HP\Documents\MCP' -Recurse -Filter '*.vsix' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($vsix) { & $code --install-extension $vsix.FullName --force; Write-Host "extension propia: $($vsix.Name)" }
    else { Write-Host "No se encontro el .vsix de vscode-mcp-bridge. Regeneralo con 'vsce package' en su carpeta." -ForegroundColor DarkYellow }
} else {
    $fallos.Add('Extensiones de VS Code (no se encontro el comando code)')
}

# ---------------------------------------------------------------------
# 6. Resumen
# ---------------------------------------------------------------------
Write-Host "`n=== Ajustes de los proyectos ===" -ForegroundColor Yellow
$proy = @('C:\Users\HP\Desktop\proyectos', (Split-Path -Parent $PSScriptRoot)) | Where-Object { $_ -and (Test-Path "$_\drawengine") } | Select-Object -First 1
if (-not $proy) {
    Write-Host "No encontre la carpeta proyectos (con drawengine dentro). Restaurala y vuelve a ejecutar este script." -ForegroundColor Red
    $fallos.Add('Carpeta proyectos no encontrada (no se hizo el venv ni el ajuste del JDK)')
} else {
    # a) Entorno virtual de drawengine
    try {
        $venv = "$proy\drawengine\venv"
        if (Test-Path $venv) { Remove-Item $venv -Recurse -Force }
        py -3.13 -m venv $venv
        & "$venv\Scripts\python.exe" -m pip install --upgrade pip
        & "$venv\Scripts\python.exe" -m pip install -r "$proy\drawengine\requirements.txt"
        if ($LASTEXITCODE -ne 0) { $fallos.Add('venv de drawengine (fallo pip install)') }
    } catch { $fallos.Add('venv de drawengine') }

    # b) Los compilar.bat tienen fija la version del JDK 17: se ajusta a la que quedo instalada
    try {
        $j17 = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($j17) {
            Get-ChildItem $proy -Recurse -Filter compilar.bat -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch '\\(build|dist|venv)\\' } | ForEach-Object {
                $txt = [IO.File]::ReadAllText($_.FullName)
                $nuevo = $txt -replace 'jdk-17[0-9.]*-hotspot', $j17.Name
                if ($nuevo -ne $txt) {
                    [IO.File]::WriteAllText($_.FullName, $nuevo, (New-Object Text.UTF8Encoding($false)))
                    Write-Host "JDK 17 ajustado en $($_.FullName)"
                }
            }
        } else { $fallos.Add('No se encontro el JDK 17 para ajustar los compilar.bat') }
    } catch { $fallos.Add('Ajuste de la ruta del JDK 17 en los compilar.bat') }
}

# c0) uv + blender-mcp: deja blender-mcp.exe en C:\Users\HP\.local\bin (la ruta que usa tu config de Claude Desktop)
try {
    Instalar 'uv' @('astral-sh.uv')
    Refrescar-Path
    $bm = 'C:\Users\HP\Documents\MCP\blender-mcp'
    if (-not (Test-Path "$bm\pyproject.toml")) {
        Write-Host "No existe $bm : restaura Documents\MCP y vuelve a ejecutar: uv tool install --force <esa carpeta>" -ForegroundColor DarkYellow
    } elseif (Get-Command uv -ErrorAction SilentlyContinue) {
        Push-Location $bm
        uv tool install --force .
        if ($LASTEXITCODE -ne 0) { $fallos.Add('Instalar blender-mcp con uv') }
        Pop-Location
    } else { $fallos.Add('uv no quedo disponible (cierra y abre la consola, luego: uv tool install --force .  dentro de blender-mcp)') }
} catch { $fallos.Add('uv / blender-mcp') }

# c) Emulador MeshCraftPhone (lo usan los emulador.bat)
try {
    $avdm = 'C:\Android\android-sdk\cmdline-tools\latest\bin\avdmanager.bat'
    if ((Test-Path $avdm) -and -not (Test-Path "$env:USERPROFILE\.android\avd\MeshCraftPhone.avd")) {
        'no' | & $avdm create avd -n MeshCraftPhone -k 'system-images;android-34;google_apis;x86_64' --device pixel_6 --force
        if ($LASTEXITCODE -ne 0) { $fallos.Add('Crear el emulador MeshCraftPhone') }
    }
} catch { $fallos.Add('Crear el emulador MeshCraftPhone') }

# d) Hypervisor de Windows (el emulador lo necesita; ademas la virtualizacion debe estar activa en la BIOS)
try {
    Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart -ErrorAction Stop | Out-Null
} catch { $fallos.Add('Activar Windows Hypervisor Platform (hazlo en Activar o desactivar caracteristicas de Windows)') }

Write-Host "`n=== 6/6 Resumen ===" -ForegroundColor Yellow
if ($fallos.Count -eq 0) {
    Write-Host "Todo se instalo sin errores." -ForegroundColor Green
} else {
    Write-Host "Esto fallo o hay que hacerlo a mano:" -ForegroundColor Red
    $fallos | ForEach-Object { Write-Host "  - $_" }
}
Write-Host @"

PENDIENTE A MANO (no se puede automatizar):
  - Reiniciar la PC una vez para que el PATH y las variables queden bien.
  - Blender: instalar las extensiones desde Edit > Preferences > Get Extensions:
      Ant Landscape, Edit Mesh Tools, LoopTools, Node Tree Screenshot, Sapling Tree Gen, Draw Hair, Ucupaint
    y el addon del puente MCP de Blender (desde Documents\MCP).
  - Restaurar tus carpetas: proyectos, Documents\MCP y C:\Users\HP\.android\debug.keystore
  - Photoshop (desde Creative Cloud), FL Studio y sus plugins (FL Cloud), Riot/Valorant,
    VirtualTablet Server, Enterprise Architect (trial) y MuseHub: instalar desde sus paginas.
  - Drivers: Windows Update + Intel Graphics + Realtek Audio.
  - Firewall: permitir el agente de Windows en redes PRIVADAS la primera vez que corra.
"@
