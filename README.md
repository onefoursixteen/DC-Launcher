# DC Launcher for Android TV

A minimal Android TV home screen with no ads, no recommendation rows and no tracking. It
replaces Google TV, PatchWall and other stock launchers.

- **Small:** 1.2 MB release APK. It depends only on Compose foundation, with no Material, no image library and no OkHttp.
- **Fast:** about 160–190 ms from process start to first frame. It uses about 33–42 MB of RAM (PSS). Measured on the Android TV 14 emulator at 4K. A real low-end TV will be slower, but in proportion.
- **Private:** no analytics, no crash reporting, no backup. The network is used only for weather and URL wallpapers, and only if you set them up.

## Features

| | |
|---|---|
| **Top bar** | Clock and date (12/24 h), optional weather (Open-Meteo, keyless), network status (Ethernet / Wi-Fi / Offline), Android system settings, launcher settings |
| **App grid** | 3–8 columns, TV banners for Leanback apps, icon tiles for sideloaded phone apps, focused tile scales 1.1× with a glow and border |
| **Tabs** | Favorites, All apps, plus your own categories (Streaming, Games, …). Tabs switch when focused, like stock TV UIs. |
| **App options** | Hold **OK** or press **Menu** on any app to open, favorite, move, add to categories, hide, see app info, or uninstall |
| **Reorder** | Choose *Move*, then use the arrow keys to move the app. **OK** or **Back** drops it. |
| **Wallpaper** | 8 gradients, 8 solid colours, an image from an `https://` URL, the system file picker, or files copied with `adb push` |
| **Settings** | Default-home helper, wallpaper, grid size, labels, clock format, weather city and units, hidden apps, categories, memory readout |

### Remote control

| Key | Action |
|---|---|
| D-pad | Move focus. Auto-repeat is throttled to about 16 steps/s so slow TVs don't overshoot. |
| OK | Open the app |
| Hold OK / Menu | App options |
| Back | Closes dialogs and move mode. Never leaves the launcher. |
| Home (while already home) | Jump back to the default tab and the first app |
| Menu / Settings (not on an app) | Open launcher settings |

## Project structure

```
TV Luncher/
├── build.gradle.kts               AGP 9.2.1 + Kotlin Compose compiler plugin 2.3.21
├── settings.gradle.kts
├── gradle.properties
├── gradlew, gradle/wrapper/       Gradle 9.6.1
└── app/
    ├── build.gradle.kts           minSdk 23, targetSdk 36, R8 + resource shrinking
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    HOME + LEANBACK_LAUNCHER filters, <queries>, banner
        ├── res/
        │   ├── drawable/banner.xml        320×180 TV banner (vector)
        │   ├── drawable/ic_launcher.xml
        │   └── values/{strings,themes}.xml
        └── java/me/cazan/zen/
            ├── ZenApp.kt              Process singletons, onTrimMemory handling
            ├── MainActivity.kt        Home activity: key handling, HOME re-press, package
            │                          broadcasts, weather/network lifecycle, app launching
            ├── SettingsActivity.kt    Default-home request (RoleManager → system screen),
            │                          wallpaper file import
            ├── data/
            │   ├── AppRepository.kt   Leanback + phone app discovery, lazy banner/icon LRU cache
            │   ├── LauncherPrefs.kt   All settings as one StateFlow over SharedPreferences
            │   ├── WallpaperLoader.kt Screen-sized RGB_565 decode, URL download cache
            │   ├── Weather.kt         Open-Meteo forecast + geocoding
            │   ├── NetworkMonitor.kt  Callback-based connectivity state
            │   └── Http.kt            Tiny HttpURLConnection helpers
            └── ui/
                ├── HomeScreen.kt      Top bar, tabs, grid, app options and category dialogs
                ├── AppTile.kt         The focus engine: key handling, long press, move mode
                ├── SettingsScreen.kt  Two-pane TV settings
                ├── WallpaperLayer.kt  Gradient and solid presets, image wallpaper and scrim
                ├── Kit.kt             Buttons, list rows, toggles, dialogs, text input
                └── Icons.kt           Canvas-drawn icons (no material-icons)
```

## Why it's fast

- **Compose foundation only.** Material, TV-Material and icon packs add startup work and APK size and bring nothing here. `Kit.kt` is the whole design system.
- **Focus effects never re-layout.** Scale, glow and border live in `graphicsLayer`/draw, so moving focus redraws one tile, not the grid.
- **Lazy, bounded art loading.** Banners (320×180) and icons (160 px) are decoded on IO only when a tile is composed. They are cached in an LRU capped at min(heap/8, 32 MB). The cache is trimmed by `onTrimMemory`.
- **Wallpapers are decoded at screen size in RGB_565.** A 4K photo on a 1080p TV costs about 4 MB instead of about 33 MB.
- **Nothing runs in the background.** The clock wakes once a minute. Weather refreshes at most every 15 min, and only while home is visible. The network status uses a callback instead of polling. There are no services, alarms or WorkManager jobs.
- **Synchronous SharedPreferences read at startup.** It is a few KB and faster than DataStore's first read.
- **No window background after the first frame.** This saves one full-screen overdraw per frame.
- **D-pad repeat throttle** in `MainActivity.dispatchKeyEvent`. Weak TV chips can't keep up with 30 Hz key repeat, and the grid "overshoots" when you let go.

---

## Setup guide

### 1. Build the APK

You need JDK 17 or newer (Android Studio's bundled JBR works) and Android SDK platform 36.

**Android Studio:** File → Open → select `TV Luncher/`, wait for Gradle sync, then Build → Build APK(s).

**Command line (macOS example):**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

The release build is signed with your **debug key**, so you can sideload it right away. Before you share the APK with anyone, create your own keystore (Build → Generate Signed APK) and point `signingConfig` in `app/build.gradle.kts` at it.

### 2. Turn on ADB on the TV

1. On the TV: **Settings → Device Preferences → About**, then press OK on **Build** seven times. This enables Developer options.
2. **Settings → Device Preferences → Developer options**: turn on **USB debugging**, and **Network debugging** if your TV has it.
3. Find the TV's IP address under **Settings → Network & Internet → (your network)**.
4. On your computer, connect and accept the prompt that appears on the TV:

```bash
adb connect 192.168.1.50:5555
```

On Android 11+ TVs that only offer **Wireless debugging**, run `adb pair <ip>:<pairing-port>` with the code shown on screen first, then `adb connect <ip>:<port>`.

### 3. Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 4. Make it the default home app

Try these in order. On Android 10+ the second one is the reliable one.

```bash
adb shell cmd package set-home-activity me.cazan.zen/.MainActivity
```

```bash
adb shell cmd role add-role-holder android.app.role.HOME me.cazan.zen 0
```

You can also open DC Launcher from the stock launcher's app list, go to **Settings → Home app → Set as default home app**, and use the system picker if your TV has one.

> **Expect this step alone to be ignored on most Android TV / Google TV builds.**
> On the Android TV 14 emulator, DC Launcher held the HOME role and the command reported
> `Success`, but HOME still opened the stock launcher. The system launcher declares a
> higher-priority HOME filter, which only a system app can do, so it wins until it is
> disabled. That's step 5.

### 5. Disable the stock launcher, safely

Use `pm disable-user`. It is **fully reversible** and survives reboots.
**Do not** use `pm uninstall` on the launcher. Never disable anything you can't name.

**a. Find your TV's home apps.** This works on any brand:

```bash
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

You'll see `me.cazan.zen/.MainActivity` plus the stock launcher(s). Common ones:

| Platform | Stock launcher package(s) |
|---|---|
| Android TV (Sony, Philips, TCL, Hisense, Nvidia Shield, emulator) | `com.google.android.tvlauncher` (older: `com.google.android.leanbacklauncher`) |
| Google TV (Chromecast with Google TV, newer Sony/TCL/Hisense) | `com.google.android.apps.tv.launcherx`, and also `com.google.android.tungsten.setupwraith`, which re-enables it |
| Xiaomi Mi TV / Mi Box with PatchWall | the `com.mitv.*` package listed by the command above |
| Amazon Fire TV | `com.amazon.tv.launcher`. Fire OS blocks disabling it, so use a remap app for the Home button instead. |

Always trust what the query command prints over this table.

**b. Disable it.** Example for Android TV:

```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

For Google TV, run this command for both packages, `launcherx` first:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

**c. Press HOME on the remote.** DC Launcher should appear. If Android asks which app to use, choose DC Launcher → **Always**.

**Undo (restore the stock launcher):**

```bash
adb shell pm enable com.google.android.tvlauncher
```

Replace the package name with whichever one you disabled.

**Things to know before disabling on Google TV:**

- Disabling `launcherx` also removes Google TV features that live inside it: profiles, the "For you" rows, some account and input pickers, and on some models the Inputs/HDMI shortcut. Your apps, Play Store, Assistant and system Settings keep working.
- Disabling `setupwraith` is what stops the launcher from coming back. A system update can re-enable either package; just run the commands again.
- Keep ADB working, and remember the `pm enable` command above. If anything goes wrong it restores the stock launcher in one line.
- System settings are always one press away: the sliders icon in DC Launcher's top bar.

### 6. (Optional) Remove bloatware

Hiding an app in DC Launcher only removes it from the home screen. To actually stop a preinstalled app from running, uninstall it for your user. This is reversible and does not touch the system partition:

```bash
adb shell pm list packages
```

```bash
adb shell pm uninstall --user 0 <package.name>
```

To restore it:

```bash
adb shell cmd package install-existing <package.name>
```

Only remove packages you can identify. Removing core services (anything with `gms`, `gsf`, `setupwizard`, `tv.settings`, `inputmethod` or `tvprovider` in the name) can break the TV.

### 7. Customize

- **Wallpaper from your computer:** copy images into the launcher's folder. They appear under *Settings → Appearance*. No storage permission is needed.
  ```bash
  adb push photo.jpg /sdcard/Android/data/me.cazan.zen/files/wallpapers/
  ```
- **Wallpaper from a URL:** *Settings → Appearance → From a URL…*. Links must be `https://` and point at a JPG, PNG or WebP file. The image is downloaded once and cached.
- **Weather:** *Settings → Clock & weather → City*, for example "Lisbon". The city's country also sets the Spotlight region.
- **Spotlight (this week's new movies and series):** get a free TMDB API key at themoviedb.org → Settings → API. On the TV, open *Settings → Spotlight* and switch on **Web admin page**. Open the address it shows from a phone or computer on the same network, then enter the PIN and your key. The key is checked with TMDB before it's saved. The page turns itself off when you leave Settings, after 15 minutes, or after 5 wrong PINs. On the home screen, press UP to reach the spotlight: LEFT/RIGHT browse, and OK shows details plus a button for each installed app that streams the title in your region. *This product uses the TMDB API but is not endorsed or certified by TMDB.*
- **Watchlist:** press OK on a spotlight title and choose *Add to watchlist*, or sign in to the web admin page and use *Search*. Watchlisted titles get a **Watchlist** tab. Once a day, while the home screen is on, DC Launcher asks TMDB where each title is streaming in your region and when the next episode airs. It labels each tile, for example *Now streaming on Netflix*, *New episode Thursday · Disney Plus*, *In cinemas, not streaming yet* or *Coming Friday*. When a title becomes watchable on an installed app, it's promoted to the front of the spotlight as *From your watchlist*. OK on a tile opens the app that has it; hold OK for details or *Remove*. The list is stored only on the TV.
- **Personal spotlight:** *Only show titles on my apps* (Settings → Spotlight, or the web page) hides anything you can't play. *Not interested* in a title's details hides it for good; *Show hidden titles again* undoes that.
- **Assistant button → any app:** remove or disable Google Assistant (`com.google.android.katniss`). Make DC Launcher the assistant handler with the command below, then choose the app in *Settings → Remote buttons*. The button then opens that app through DC Launcher's invisible `AssistActivity`.
  ```bash
  adb shell cmd role add-role-holder android.app.role.ASSISTANT me.cazan.zen 0
  ```
- **Network & VPN:** the network icon in the top bar is a button. When a VPN is active it turns into a shield labelled *VPN*. Pressing it shows the connection type, the TV's IP address and the VPN state, with a *Disconnect in …* entry for each installed VPN app (or *Connect with …* when it's off). Android only lets the VPN app itself switch the VPN off, so DC Launcher opens that app on its connection screen; one more OK disconnects.
- **Colours, gradients, spacing:** edit `Zen` in `ui/Kit.kt` and the presets in `ui/WallpaperLayer.kt`.

## Troubleshooting

| Problem | Fix |
|---|---|
| HOME still opens the stock launcher | Do step 5. On most TVs the stock launcher has to be disabled. |
| A system update brought the old launcher back | Re-run the `pm disable-user` commands from step 5 |
| An app shows an icon instead of a banner | It's a phone app with no TV banner. DC Launcher draws its icon on a tinted tile. |
| Weather or URL wallpapers fail on Android 6–7 | These TVs ship outdated HTTPS root certificates. Use `adb push` for wallpapers. Weather may not be available. |
| A newly installed app doesn't show up | Installs are detected live. If it's missing, check *Settings → Hidden apps* and make sure it has a launcher entry. |
| Black screen with no launcher | `adb shell pm enable <stock launcher package>` restores the stock launcher immediately |
