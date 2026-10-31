# Builds the Hem launcher APK without Gradle and installs it on the TV.
# Usage: .\build.ps1             (build + install)
#        .\build.ps1 -SetHome    (build + install + make Hem the home screen and disable the stock launcher)
#        .\build.ps1 -NoInstall  (build only)
# Needs JDK 17 ($env:JAVA_HOME or ~\Android\jdk-17*) and the Android SDK ($env:ANDROID_HOME or ~\Android\sdk)
# with platforms;android-34 and build-tools;34.0.0.
param([switch]$NoInstall, [switch]$SetHome)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$javaHome = if ($env:JAVA_HOME) {
    $env:JAVA_HOME
} else {
    Get-ChildItem "$env:USERPROFILE\Android" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:USERPROFILE\Android\sdk" }
$buildTools = "$sdk\build-tools\34.0.0"
$androidJar = "$sdk\platforms\android-34\android.jar"
if (-not $javaHome -or -not (Test-Path "$javaHome\bin\javac.exe")) { throw 'JDK 17 not found. Set JAVA_HOME.' }
if (-not (Test-Path $androidJar) -or -not (Test-Path $buildTools)) {
    throw 'Android SDK not found. Set ANDROID_HOME and install platforms;android-34 and build-tools;34.0.0.'
}

$out = "$PSScriptRoot\build"
$keystore = "$PSScriptRoot\keystore\home.jks"
$storePass = if ($env:HEM_KEYSTORE_PASS) { $env:HEM_KEYSTORE_PASS } else { 'makeover' }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

# 1. Manifest + assets -> base APK
& "$buildTools\aapt2.exe" link -o "$out\base.apk" -I $androidJar -A "$PSScriptRoot\assets" `
    --manifest "$PSScriptRoot\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 26

# 2. Java -> classes -> dex
$sources = Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter *.java | ForEach-Object FullName
& "$javaHome\bin\javac.exe" -encoding UTF-8 --release 8 -Xlint:-options `
    -classpath $androidJar -d "$out\classes" @sources
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
$env:JAVA_HOME = $javaHome
& "$buildTools\d8.bat" --release --min-api 26 --lib $androidJar --output "$out\dex" @classes

# 3. Add classes.dex to the APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

# 4. Align and sign. Keep the keystore: updates must be signed with the same key.
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force (Split-Path $keystore) | Out-Null
    & "$javaHome\bin\keytool.exe" -genkeypair -keystore $keystore -alias home -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $storePass -keypass $storePass -dname 'CN=Hem'
}
& "$buildTools\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass "pass:$storePass" --out "$out\Hem.apk" "$out\aligned.apk"
Write-Host "Built $out\Hem.apk"

# 5. Install, and optionally make Hem the home screen
if (-not $NoInstall) {
    . "$PSScriptRoot\..\common.ps1"
    & $adb -s $tv install -r "$out\Hem.apk"
    if ($SetHome) {
        & $adb -s $tv shell cmd package set-home-activity se.makeover.home/.MainActivity
        $result = (& $adb -s $tv shell pm disable-user --user 0 com.google.android.tvlauncher) -join ' '
        $log = "$PSScriptRoot\..\backup\disabled.txt"
        New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
        if ($result -match 'disabled' -and 'com.google.android.tvlauncher' -notin @(Get-Content $log -ErrorAction SilentlyContinue)) {
            Add-Content $log 'com.google.android.tvlauncher'
        }
        Write-Host 'Hem is now the home screen; the stock launcher is disabled (restore.ps1 brings it back).'
    }
}
