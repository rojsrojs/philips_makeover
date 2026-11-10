# CLAUDE.md

Debloat and custom-launcher project for 2018 Philips Android TVs (tested: 55PUS7363/12, TPM171E chassis,
Android TV 8.0 / API 26, 1.9 GB RAM). Everything is driven over network ADB from Windows PowerShell 7.
See README.md for the user-facing steps.

## Layout

- `common.ps1` is dot-sourced by every script. It provides `$adb` and `$tv`, reading the TV address from
  `$env:PHILIPS_TV` or `tv-address.txt` (git-ignored), and connects.
- `debloat.ps1`, `limit-background.ps1`, `restore.ps1`, `push-backgrounds.ps1`: TV changes. Each change is
  logged in `backup/` (git-ignored, per TV): `disabled.txt`, `background_limited.txt`, plus a one-time
  snapshot of packages and global settings.
- `home-app/`: **Hem**, the launcher. Package `se.makeover.home`, plain Java 8, no AndroidX, no Gradle,
  no XML layouts (views are built in code). `build.ps1` runs aapt2 → javac `--release 8` → d8 →
  zipalign → apksigner. Use `-NoInstall` to only build and `-SetHome` on first install.
  - `MainActivity`: grid of favourite banners (4 columns, left 58% of the screen), top bar, dialogs,
    background image (FIT_END, right-aligned) with crossfade and a 10 s slideshow.
  - `Favorites`: ordered package list in SharedPreferences (`home` → `favorites`).
  - `Backgrounds`: images in `getExternalFilesDir()/backgrounds`; decoding, upload processing (EXIF
    rotation, resize, JPEG, auto-name "Bild N"), renaming, natural sort.
  - `UploadServer`: minimal HTTP server (port 8080–8090), only alive while the upload dialog is open,
    4-digit PIN; the page is `assets/upload.html` and prefills the PIN from `?pin=`.
  - `QrCode`: dependency-free QR encoder (byte mode, level M, versions 1–6) for the upload link. Verify
    changes by decoding a screenshot, for example with `zxing-cpp` in a throwaway venv.
  - `RunningApps`: the "Aktiva appar" task manager. It parses `dumpsys activity o` (run by the app
    itself), keeps launchable non-system packages and sums PSS via `getProcessMemoryInfo`. Force quit
    uses `killBackgroundProcesses` and falls back to the system app page when the app survives.
  - The background menu is labelled "Slideshow" in the UI.
  - The UI text is Swedish.

## Rules

- Only make reversible changes: `pm disable-user --user 0`, `cmd appops set … RUN_IN_BACKGROUND ignore`,
  `settings put`. Never uninstall system apps. Log every change in `backup/` so `restore.ps1` can undo it.
- Ask before disabling anything outside the reviewed list in `debloat.ps1`, and never touch core
  `org.droidtv.*` (tuner, inputs, settings, Ambilight), `com.android.*`, Play Services/Store, the
  keyboard or `com.google.android.katniss` (voice search / remote microphone).
- Never commit `home-app/keystore/`, `tv-address.txt`, `backup/` or `backgrounds/*`. Updates of Hem
  must be signed with the same keystore.
- Keep Hem light: measure with `dumpsys meminfo se.makeover.home` (the TOTAL PSS line). Compare like
  with like: `ps -o RSS` includes shared libraries and is much higher than PSS.
- Verify changes on the device: `adb shell screencap -p` and pull the PNG, check `logcat` for
  `FATAL EXCEPTION` and drive the UI with `input keyevent`.

## Device findings

- **No authorisation popup:** revoke authorisations, toggle USB debugging and do a real restart
  (not standby). The approval is then remembered.
- **`cmd package set-home-activity <component>`** switches the launcher without a picker. Uninstalling
  the current default launcher clears the choice, so set it again. The stock launcher is
  `com.google.android.tvlauncher/.MainActivity`; disable it, or the system starts it at boot anyway.
- **Netflix** (`com.netflix.ninja`) is on the system device-idle whitelist and starts from
  `BOOT_COMPLETED`. App-ops limits don't apply, `pm disable` of components is refused for shell, and
  `deviceidle whitelist -pkg` can't remove system entries.
- **`org.droidtv.nettvbrowser`** is a persistent system app. It keeps starting after `disable-user`,
  but with a much smaller footprint.
- **Useful Philips activities:** `org.droidtv.channels/.sources.SourcesDrawerActivity` (Sources),
  `org.droidtv.playtv/.PlayTvActivity` (live TV); `android.settings.SETTINGS` resolves to the Philips
  settings menu.
- **Telia Play** (`com.teliasonera.telia.playplus`) was seen using a full CPU core in the background,
  which is a good reason to limit it.
- **Shell access:** the shell user can write to `/sdcard/Android/data/se.makeover.home/files/`, but not
  to the app's private SharedPreferences (the app isn't debuggable). Renaming files from adb therefore
  loses the slideshow selection.
- **Apps reading process info:** on Android 8, an app can run `dumpsys activity` only with
  `DUMP` (`pm grant`) **and** `PACKAGE_USAGE_STATS` granted as a permission (`pm grant`, not just
  `appops set GET_USAGE_STATS allow`). `build.ps1` grants both after each install. The oom list
  writes small indexes as `Proc # 5:` (with a space).
- **`killBackgroundProcesses`** (a normal permission) stops cached and service processes, including
  Netflix, but not audible or visible ones. `FORCE_STOP_PACKAGES` can't be granted.
- **`adb shell input keyevent --longpress`** does not trigger a long press in Hem. It acts as a click.
- **Philips telemetry:** `org.droidtv.candeebug` logs app-usage events. It is not in `debloat.ps1`
  because its role is unclear; test carefully before adding it.
- **Building:** javac with `-bootclasspath android.jar` fails on lambdas (no `LambdaMetafactory`). Use
  `--release 8 -classpath android.jar`.
