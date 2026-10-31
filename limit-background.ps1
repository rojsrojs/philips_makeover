# Limits an app from running in the background on the TV. The app still works when opened.
# Usage: .\limit-background.ps1                              (list apps and their status)
#        .\limit-background.ps1 com.example.app              (limit)
#        .\limit-background.ps1 com.example.app -Undo        (allow again)
param([string]$Package, [switch]$Undo)

. "$PSScriptRoot\common.ps1"

$log = "$PSScriptRoot\backup\background_limited.txt"

if (-not $Package) {
    $apps = & $adb -s $tv shell cmd package query-activities --brief -a android.intent.action.MAIN `
        -c android.intent.category.LEANBACK_LAUNCHER |
        Where-Object { $_ -match '/' } | ForEach-Object { ($_.Trim() -split '/')[0] } | Sort-Object -Unique
    foreach ($app in $apps) {
        $mode = (& $adb -s $tv shell cmd appops get $app RUN_IN_BACKGROUND) -join ' '
        $status = if ($mode -match 'ignore') { 'limited' } else { 'allowed' }
        '{0,-8} {1}' -f $status, $app
    }
    return
}

New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
$limited = @(Get-Content $log -ErrorAction SilentlyContinue)
if ($Undo) {
    & $adb -s $tv shell cmd appops set $Package RUN_IN_BACKGROUND allow
    $limited | Where-Object { $_ -ne $Package } | Set-Content $log
    "Allowed in background: $Package"
} else {
    & $adb -s $tv shell cmd appops set $Package RUN_IN_BACKGROUND ignore
    & $adb -s $tv shell am force-stop $Package
    if ($Package -notin $limited) { Add-Content $log $Package }
    "Limited in background: $Package"
}
