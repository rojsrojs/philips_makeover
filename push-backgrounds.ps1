# Copies the images in .\backgrounds to the Hem launcher on the TV.
# (Uploading from a phone via Bakgrund -> Ladda upp från mobilen is usually easier.)
# Usage: .\push-backgrounds.ps1          (copy images to the TV)
#        .\push-backgrounds.ps1 -Mirror  (also delete images on the TV that are not in the folder,
#                                         including ones uploaded from a phone)
param([switch]$Mirror)

. "$PSScriptRoot\common.ps1"

$dest = '/sdcard/Android/data/se.makeover.home/files/backgrounds'
& $adb -s $tv shell mkdir -p $dest

$local = Get-ChildItem "$PSScriptRoot\backgrounds" -File |
    Where-Object { $_.Extension.ToLower() -in '.jpg', '.jpeg', '.png', '.webp' }

foreach ($file in $local) {
    & $adb -s $tv push $file.FullName "$dest/$($file.Name)" | Out-Null
    "Pushed  $($file.Name)"
}

if ($Mirror) {
    $names = $local | ForEach-Object Name
    foreach ($remote in (& $adb -s $tv shell ls $dest) -split "`r?`n" | Where-Object { $_ }) {
        if ($remote -notin $names) {
            & $adb -s $tv shell rm "$dest/$remote"
            "Removed $remote"
        }
    }
}
