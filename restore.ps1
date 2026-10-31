# Undoes the makeover: re-enables every app in backup\disabled.txt, lifts background limits,
# makes the stock launcher the home screen again and resets animations.
# Usage: .\restore.ps1                      (restore everything)
#        .\restore.ps1 com.netflix.ninja    (re-enable one package)
param([string]$Package)

. "$PSScriptRoot\common.ps1"

$pkgs = if ($Package) { @($Package) } else { Get-Content "$PSScriptRoot\backup\disabled.txt" }
foreach ($p in $pkgs) {
    & $adb -s $tv shell pm enable $p
}

if (-not $Package) {
    foreach ($p in Get-Content "$PSScriptRoot\backup\background_limited.txt" -ErrorAction SilentlyContinue) {
        & $adb -s $tv shell cmd appops set $p RUN_IN_BACKGROUND allow
        "Background allowed: $p"
    }
    & $adb -s $tv shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
    foreach ($setting in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
        & $adb -s $tv shell settings put global $setting 1.0
    }
    'Stock launcher is the home screen again; animations reset to 1x'
}
