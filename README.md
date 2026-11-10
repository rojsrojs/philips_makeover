# Philips Android TV makeover

Make a slow 2018 Philips Android TV fast again, without root and without removing anything permanently:

- **Debloat:** disable unused Google/Philips apps and viewing-data tracking (Inscape ACR) over ADB.
- **Limit background apps:** stop selected streaming apps from running when you are not using them.
- **Replace the home screen** with **Hem**, a tiny custom launcher: your favourite apps on the left, your own photos on the right, uploaded straight from your phone.

Every change is reversible with one script (`restore.ps1`), and a factory reset also restores everything.

> The Hem user interface is in **Swedish**. The strings live in `home-app/src/se/makeover/home/MainActivity.java` and `home-app/assets/upload.html` if you want to translate them.

## Tested on

| | |
|---|---|
| TV | Philips 55PUS7363/12 (2018) |
| Chassis | TPM171E |
| OS | Android TV 8.0 (Oreo), 1.9 GB RAM |
| PC | Windows 11, PowerShell 7 |

Other Philips Android TVs from the same years are likely to work, but package names can differ. `debloat.ps1` skips packages that are not installed, and the Hem launcher should run on any Android TV 8.0 or later. The **Källor** (Sources) button is Philips-specific.

On the test TV, free memory went from about 45 MB to 100–300 MB depending on what was open. Hem uses about 20–35 MB of its own memory.

## Prerequisites

### On the TV: enable developer mode and USB debugging

Menu names vary a little between firmware versions and languages.

1. Open **Settings → All settings → Android settings → About** (on some versions: **Device preferences → About**).
2. Select **Build** and press **OK** on the remote **7 times** until it says you are a developer.
3. Go back to Android settings and open **Developer options**. Turn on **USB debugging**. On these TVs this also enables debugging over the network.
4. Note the TV's IP address (see [step 1](#1-point-the-scripts-at-your-tv)). A wired connection is the most reliable.

### On the PC (Windows)

- **PowerShell 7** (`winget install Microsoft.PowerShell`)
- **ADB:** `winget install Google.PlatformTools`
- **To build Hem:** JDK 17 and the Android SDK command-line tools. No Android Studio or Gradle is needed, and neither needs admin rights:
  1. Download the JDK 17 ZIP from [Adoptium](https://adoptium.net/) and extract it to `%USERPROFILE%\Android\` (or set `JAVA_HOME`).
  2. Download *Command line tools only* for Windows from [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only). Extract it so that this file exists: `%USERPROFILE%\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat` (or set `ANDROID_HOME`).
  3. Install the SDK packages:
     ```powershell
     $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\Android\jdk-17*")[0].FullName
     & "$env:USERPROFILE\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
     & "$env:USERPROFILE\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0"
     ```

## Step by step

### 1. Point the scripts at your TV

**Find the TV's IP address:** on the TV, open **Settings → All settings → Wireless and networks → Wired or Wi-Fi → View network settings**. The menu names vary a little by firmware. On a home network the address usually starts with `192.168.`, for example `192.168.0.23` or `192.168.1.50`. Some routers use `10.` or `172.16.`–`172.31.` instead. Your router's app or web page also lists it.

Create `tv-address.txt` in the project root and put the address on the first line. The file is git-ignored, so your address stays on your PC.

```text
192.168.1.50
```

(This is an example. Use your TV's own address.) You can set `$env:PHILIPS_TV` instead.

Tip: reserve the address for the TV in your router (often called *DHCP reservation* or *static lease*). Otherwise it can change after a router restart, and the scripts will not find the TV.

### 2. Connect and authorise

```powershell
adb connect 192.168.1.50:5555
adb devices
```

A popup appears on the TV. Tick **Always allow from this computer** and press **OK**. `adb devices` should then show `device`, not `unauthorized`.

**No popup?** Press Home so nothing covers the screen. In Developer options, choose **Revoke USB debugging authorisations** and switch USB debugging off and on. Then **restart the TV properly**: **Settings → Android settings → About → Restart**, or unplug it for a minute. Standby (the power button on the remote) is not enough. Connect again.

### 3. Debloat

```powershell
.\debloat.ps1 -WhatIf   # see what would be disabled
.\debloat.ps1           # disable it and set animations to 0.5x
```

The first run saves a snapshot of the TV's packages and settings to `backup\`, and every disabled package is logged in `backup\disabled.txt`. Read the list in `debloat.ps1` first. Remove anything you use, such as Alexa, and add apps you never use.

### 4. Build and install Hem

```powershell
.\home-app\build.ps1 -SetHome
```

This builds the APK, installs it, makes Hem the home screen and disables the stock Google launcher, which would otherwise keep running in the background. The first build creates a signing key in `home-app\keystore\`. **Back it up**: future updates of Hem must be signed with the same key.

Later builds only need `.\home-app\build.ps1`.

### 5. Limit background apps (optional)

```powershell
.\limit-background.ps1                       # list apps: limited / allowed
.\limit-background.ps1 com.example.app       # limit
.\limit-background.ps1 com.example.app -Undo # allow again
```

A limited app works normally when you open it, but cannot run in the background. It may start a few seconds slower, and casting to it from a phone may only work while it is open. Don't limit **YouTube** (casting) or the **Play Store** (app updates).

### 6. Restart

```powershell
adb reboot
```

## Using Hem

- **Favourites grid:** open an app with **OK**. **Hold OK** on a tile to **Flytta** (move, using the arrow keys, then OK), **Tvinga avslutning** (force quit) or **Ta bort från favoriter** (remove). The **+** tile adds any installed app.
- **Top bar:** clock, **Källor** (inputs/HDMI), **Aktiva appar**, **Slideshow**, **Inställningar** (the normal TV settings) and **Om** (Android's About page, with **Restart** already selected, so restarting is one press of OK. Apps can't restart the TV directly).
- **Aktiva appar** is a small task manager. It lists your running apps with their memory use and shows free memory in the title. Select an app to force quit it. System and Philips processes are not listed.
  - Force quit stops the app's processes. Android won't let an ordinary app stop something that is audible or on screen (for example music playing), so in that case Hem opens the app's system page, where you press **Tvångsstoppa**.
  - It needs two permissions that only adb can grant (`DUMP` and `PACKAGE_USAGE_STATS`). `build.ps1` grants them on every install.- **Slideshow:**
  - **Ladda upp från mobilen:** the TV shows a QR code. Scan it with a phone on the same network to open the upload page with the code already filled in, or type the address and 4-digit code shown next to it. Photos are rotated upright, resized for the TV and named *Bild 1, Bild 2, …*
  - **Välj bilder:** tick the photos to show. None gives a black background, one gives a still image, and several give a slideshow that changes every 10 seconds. A small label in the bottom right corner shows the image's number, or its name if you renamed it.
  - **Byt namn / Ta bort bilder:** rename or delete photos.
- Images can also be copied from the PC: put them in `backgrounds\` and run `.\push-backgrounds.ps1`.
- The default favourites are a set of Nordic streaming apps (`Favorites.java`). Apps that aren't installed are skipped, so just edit the grid on the TV.
- **Installing new apps:** add **Google Play Store** with the **+** tile, install apps from it, then add them with **+**. The Voice button and the remote's own buttons (Netflix, Ambilight, Settings) work as before.

## Undo

```powershell
.\restore.ps1                    # everything: re-enable apps, lift limits, stock launcher, 1x animations
.\restore.ps1 com.example.app    # re-enable a single app
adb uninstall se.makeover.home   # remove Hem (after restore.ps1)
```

## Known limitations

- **Netflix keeps auto-starting.** Philips puts it on a system power whitelist so the Netflix button opens instantly. Background limits have no effect on it without root.
- **The Net TV browser** (`org.droidtv.nettvbrowser`) is a persistent system process. After disabling, it still starts but uses much less memory.
- **Voice search** (`com.google.android.katniss`) uses a lot of memory but powers the remote's microphone, so it is left alone. The remote needs to be paired (Bluetooth) for the microphone and the back keyboard to work.
- **Uninstalling a launcher** clears the home-screen choice. Run `adb shell cmd package set-home-activity se.makeover.home/.MainActivity` afterwards.
- The phone upload page is plain HTTP on your local network. It only runs while the upload dialog is open on the TV and requires the code shown there.

## Project layout

```text
common.ps1              finds adb, reads the TV address, connects
debloat.ps1             backup + disable bloat + faster animations
limit-background.ps1    background limits per app
restore.ps1             undo everything (or one app)
push-backgrounds.ps1    copy images from .\backgrounds to Hem
home-app/
  build.ps1             Gradle-free build: aapt2 → javac → d8 → zipalign → apksigner
  AndroidManifest.xml
  assets/upload.html    the phone upload page
  src/se/makeover/home/ MainActivity (UI), Favorites, Backgrounds, UploadServer
```

## Disclaimer

Not affiliated with Philips, TP Vision or Google. You use this at your own risk. Everything here uses `pm disable-user` and app-ops instead of uninstalling, so a factory reset always brings the TV back to stock.
