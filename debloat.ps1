# Backs up the TV's package state, then disables bloat and tracking apps and speeds up animations.
# Everything is reversible with restore.ps1. Packages that are not installed are skipped.
# Usage: .\debloat.ps1 -WhatIf   (show what would be disabled)
#        .\debloat.ps1           (do it)
param([switch]$WhatIf)

. "$PSScriptRoot\common.ps1"

$backup = "$PSScriptRoot\backup"
$log = "$backup\disabled.txt"
New-Item -ItemType Directory -Force $backup | Out-Null

# Snapshot of the original state, taken once.
if (-not (Test-Path "$backup\packages_all.txt")) {
    & $adb -s $tv shell pm list packages -f > "$backup\packages_all.txt"
    & $adb -s $tv shell pm list packages -d > "$backup\packages_disabled_before.txt"
    & $adb -s $tv shell pm list packages -3 > "$backup\packages_user_installed.txt"
    & $adb -s $tv shell settings list global > "$backup\settings_global_before.txt"
    "Saved backup to $backup"
}

$packages = @(
    # Google extras
    'com.google.android.videos'                            # Play Movies
    'com.google.android.music'                             # Play Music (service shut down)
    'com.google.android.youtube.tvmusic'                   # YouTube Music
    'com.google.android.play.games'                        # Play Games
    'com.google.android.tvtutorials'                       # Android TV tutorials
    'com.google.android.feedback'                          # Feedback reporting
    'com.google.android.tvrecommendations'                 # Recommendation service
    'com.google.android.leanbacklauncher'                  # Old, unused Google launcher
    'com.google.android.leanbacklauncher.recommendations'  # Its recommendation service

    # Philips extras and tracking
    'tv.inscape.tvclient.inscapeacr'                       # Inscape ACR: recognises what is on screen
    'org.droidtv.usagelogger'                              # Usage logging
    'org.droidtv.nettvrecommender'                         # Philips recommendations
    'org.droidtv.demome'                                   # Shop demo mode
    'org.droidtv.twittershare'                             # Twitter sharing
    'org.droidtv.amazonalexa'                              # Alexa setup
    'org.droidtv.nettv.market'                             # Philips App Gallery
    'org.droidtv.nettvbrowser'                             # Net TV browser (web apps, HbbTV red button)
    'org.droidtv.nettvapp1814'                             # Net TV web apps
    'org.droidtv.nettvapp4649'
    'org.droidtv.nettvapp4768'
    'org.droidtv.nettvapp4771'
    'org.droidtv.nettvapp4836'
    'org.droidtv.nettvapp5225'
    'com.opera.sdk.example'                                # Opera SDK demo browser

    # Discontinued or replaced streaming apps
    'se.cmore.bonnier'                                     # C More (merged into TV4 Play)
    'se.viafree.android'                                   # Viafree (merged into Viaplay)
    'com.hbo_android_tv'                                   # HBO Nordic (replaced by Max)
    'com.hbo.hbonow'                                       # Old HBO Max app (replaced by com.wbd.stream)
)

$installed = (& $adb -s $tv shell pm list packages) -replace '^package:'
$disabled = (& $adb -s $tv shell pm list packages -d) -replace '^package:'
$logged = @(Get-Content $log -ErrorAction SilentlyContinue)

foreach ($package in $packages) {
    if ($package -notin $installed) { "skip     $package (not installed)"; continue }
    if ($package -in $disabled) { "skip     $package (already disabled)"; continue }
    if ($WhatIf) { "would    $package"; continue }
    $result = (& $adb -s $tv shell pm disable-user --user 0 $package) -join ' '
    if ($result -match 'new state: disabled') {
        if ($package -notin $logged) { Add-Content $log $package }
        "disabled $package"
    } else {
        "FAILED   $package -> $result"
    }
}

if (-not $WhatIf) {
    foreach ($setting in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
        & $adb -s $tv shell settings put global $setting 0.5
    }
    'Animations set to 0.5x'
}
