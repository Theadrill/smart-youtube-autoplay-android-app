param(
    [string]$ApkPath = "app\\build\\outputs\\apk\\debug\\app-debug.apk",
    [switch]$Build,
    [string]$Connect
)

function Resolve-Adb {
    $adb = "adb"
    try {
        $null = & $adb version 2>$null
        return $adb
    } catch {
        $localProps = Join-Path $PSScriptRoot 'local.properties'
        if (Test-Path $localProps) {
            $sdkLine = (Get-Content $localProps | Select-String -Pattern '^sdk.dir=' | Select-Object -First 1)
            if ($sdkLine) {
                $sdkDir = $sdkLine.ToString().Substring(8) -replace '\\\\', '\'
                $sdkDir = $sdkDir -replace '\\:', ':'
                $adbPath = Join-Path $sdkDir 'platform-tools\\adb.exe'
                if (Test-Path $adbPath) { return $adbPath }
            }
        }
        throw "ADB não encontrado no PATH nem em local.properties"
    }
}

function Install-Apk-All {
    param(
        [string]$Path = $ApkPath,
        [switch]$BuildApk,
        [string]$ConnectSerial = $Connect
    )

    Push-Location $PSScriptRoot
    try {
        if ($Build -or $BuildApk) {
            Write-Host "== Build assembleDebug"
            if (Test-Path .\gradlew.bat) { .\gradlew.bat --no-daemon assembleDebug } else { ./gradlew assembleDebug }
        }

        if (-not (Test-Path $Path)) { throw "APK não encontrado: $Path" }

        $adb = Resolve-Adb
        & $adb start-server | Out-Null

        if ($ConnectSerial) {
            Write-Host "== Conectando via ADB: $ConnectSerial"
            & $adb connect $ConnectSerial | Out-Null
        }

        $devices = & $adb devices | Select-String -Pattern '^[^\s]+\s+device(\s|$)' | ForEach-Object { $_.Line.Split()[0] }
        if (-not $devices) { throw "Nenhum dispositivo 'device' disponível via ADB" }

        if ($ConnectSerial) {
            $devices = $devices | Where-Object { $_ -eq $ConnectSerial }
            if (-not $devices) { throw "Dispositivo conectado não encontrado: $ConnectSerial" }
        }

        foreach ($d in $devices) {
            Write-Host "== Instalando em: $d"
            & $adb -s $d install -r $Path
        }
    } finally {
        Pop-Location
    }
}

Set-Alias -Name install-apk-all -Value Install-Apk-All -Scope Local -Force

if ($MyInvocation.InvocationName -eq $MyInvocation.MyCommand.Name) {
    Install-Apk-All -Path $ApkPath -BuildApk:$Build -ConnectSerial:$Connect
}
