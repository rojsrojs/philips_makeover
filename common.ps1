# Shared setup for the scripts: finds adb and connects to the TV.
# The TV address comes from $env:PHILIPS_TV or the first line of tv-address.txt (git-ignored),
# e.g. 192.168.1.50. Port 5555 is added if missing. Dot-source this file: . "$PSScriptRoot\common.ps1"

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($adbCommand) {
    $adbCommand.Source
} else {
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
}
if (-not (Test-Path $adb)) { throw 'adb not found. Install it with: winget install Google.PlatformTools' }

$address = $env:PHILIPS_TV
$addressFile = Join-Path $PSScriptRoot 'tv-address.txt'
if (-not $address -and (Test-Path $addressFile)) { $address = (Get-Content $addressFile -TotalCount 1).Trim() }
if (-not $address) { throw "Put the TV's IP address in tv-address.txt (e.g. 192.168.1.50) or set `$env:PHILIPS_TV." }
$tv = if ($address -match ':\d+$') { $address } else { "${address}:5555" }

& $adb connect $tv | Out-Null
