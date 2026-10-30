# Hearth for Fire TV

The same home screen as the LG version (clock, date, weather, your apps in one or two rows, wallpaper or a USB slideshow, all the looks and fonts), running on an Amazon Fire TV. It was made for a **Fire TV Stick 4K on Fire OS 6.7.1.1** (Android 7.1), and should also run on other Fire TVs from Fire OS 5.

The web page is the same code as the LG version. Around it is a small Android app (`android/`) that does what webOS did for the LG version: lists the installed apps and their icons, opens apps, reads pictures from storage or a USB drive, and (the part you asked for) makes Hearth open instead of Amazon's home screen.

## What is different from the LG version

| LG version | Fire TV version |
|---|---|
| Homebrew Channel, root | Not needed. The Fire TV is not rooted |
| Live app list through the root shell | The Android app lists what is installed, with each app's own icon |
| "LG Home" tile | **Fire TV Home** tile (opens Amazon's home screen) |
| HDMI inputs row, TV input, LG logo, SAM/helper-script options | Gone (a Fire TV Stick has none of these) |
| Recent apps panel (Back on the home row) | Gone. Back on the home row goes to the first tile |
| Wallpaper from USB / `/media/developer` | Wallpaper from a USB drive (OTG cable) or the Fire TV's own storage |

Everything else is unchanged: themes, fonts, colours, icon shapes and sizes, quiet mode, OLED shift, the weather (Open-Meteo), hiding, moving and renaming apps.

## 1. Get the app (an .apk file)

The tools that build an Android app were not available where this port was written, so **the app has not been compiled or tried on a real Fire TV yet**. You need to build it once. Pick one:

### A. In GitHub's cloud (no installs, easiest)

1. Make a free GitHub account, create a new repository, and upload everything in this folder to it (**Add file → Upload files**; drag the folder's contents in).
   Hidden folders are often skipped by the upload. If the repository has no `.github` folder afterwards, choose **Add file → Create new file**, type `.github/workflows/firetv-apk.yml` as the name, paste in the text from `build-firetv-apk.yml.txt` (everything below the dashed line), and commit.
2. Open the repository's **Actions** tab, choose **Build Fire TV app**, then **Run workflow**.
3. After a few minutes, open the finished run. At the bottom, under **Artifacts**, download **Hearth-FireTV** (a zip with `Hearth-FireTV.apk` inside).

If the build fails, open the failed step and send me the red error text: it will be a small fix.

### B. With Android Studio

1. Install Node 18 or newer and Android Studio.
2. In this folder: `npm install`, then `npm run build:firetv` (builds the page and copies it into the app).
3. Open the `android` folder in Android Studio and choose **Build → Build APK(s)**. The file is `android/app/build/outputs/apk/debug/app-debug.apk`.

## 2. Install it on the Fire TV Stick

1. Fire TV: **Settings → My Fire TV → Developer Options** → turn on **ADB debugging** and **Apps from Unknown Sources**. (If Developer Options is missing: Settings → My Fire TV → About, then press OK on the device name 7 times.)
2. Note the stick's address: Settings → My Fire TV → About → Network (for example 192.168.1.20).
3. On a computer with `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools)) on the same Wi-Fi:

```
adb connect 192.168.1.20:5555
adb install -r Hearth-FireTV.apk
adb shell pm grant com.hearth.firetv android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.hearth.firetv android.permission.READ_EXTERNAL_STORAGE
```

The first time, the Fire TV asks you to allow the connection: choose **Always allow** and OK. The two `pm grant` lines are the one-time setup that lets Hearth switch its Home-button helper on by itself and read your pictures. (No computer? Install with the **Downloader** app by typing the address of the .apk, and use Settings → Accessibility on the Fire TV for step 3 below instead.)

4. Open Hearth: Settings → Applications → Manage Installed Applications → Hearth → Launch, or find it in "Your Apps & Channels".

## 3. Make Hearth your main launcher

Fire OS does not let any app take over the Home button, and it has no "default home app" choice. Hearth works around that in two ways. Use the first; add the second only if you want it.

### Method 1 (recommended, fully reversible): the Home helper

In Hearth: **Settings → Options → Open Hearth when Home is pressed → OK**.

This switches on a small Accessibility service. It only notices when Amazon's home screen comes to the front (Home button, Back out of an app, start-up) and puts Hearth in front of it. It cannot read the screen (the service is declared without that permission). Amazon's home may flash for a moment before Hearth covers it.

Also turn on **Open Hearth when the Fire TV starts**, so you land in Hearth after a restart.

If you skipped the `pm grant ... WRITE_SECURE_SETTINGS` line, pressing OK opens the Accessibility settings instead (if your Fire TV has that screen): switch **Hearth** on there. To turn it off again, press OK on the same row.

**Check that it works:** press Home on the remote while an app is open (Hearth should appear), then open Settings → Options → **Test the Home button** and press OK. It says whether the helper is running, which home screens it watches for, which screens it has seen, and whether Hearth has really taken over ("RESULT: working"). If it says otherwise, send me that text and I can fix it exactly.

The **Fire TV Home** tile opens Amazon's home screen and keeps the helper out of the way for 30 seconds so you can use it. After that, Home takes you back to Hearth.

### Method 2 (stronger, optional): switch Amazon's home app off

With Amazon's home app disabled there is nothing for Home to open except Hearth, so there is no flash and no helper is needed. From a computer:

```
adb shell pm disable-user --user 0 com.amazon.tv.launcher
```

To undo it at any time:

```
adb shell pm enable com.amazon.tv.launcher
```

Please read this before you do it:

- Do it **only after** Method 1 works and you have checked that Hearth opens your apps. Keep ADB debugging on, so you can undo it.
- With Amazon's home gone, the **Fire TV Home** tile has nothing to open. **System Settings** keeps working.
- Some Fire TV features that live in Amazon's home app (parts of search, voice, notifications) may stop working while it is disabled. Fire OS updates may switch it back on; run the command again if so.
- The Fire TV may also ask which home app to use the first time you press Home. Choose **Hearth** and **Always**.

## Using it

The remote works as on the LG version: arrows move, **OK** opens, **hold OK** opens an app's menu (move, hide, rename, uninstall), **Back** goes back one screen (on the home row it goes to the first tile). Uninstalling asks you to confirm on a system screen. Apps that came with the Fire TV cannot be uninstalled.

**Wallpaper:** Settings → Wallpaper → OK lists your USB drive and the Fire TV's own storage. Allow the storage permission the first time (or use the `pm grant` line above). Put pictures in `Pictures` on the stick (`adb push photo.jpg /sdcard/Pictures/`) or on a USB drive. JPG or PNG, under 8 MB and 25 megapixels, ideally around 1920×1080.

**Apps row:** every app with a launcher entry is listed, with popular streaming apps first. Hide the ones you do not want in Settings → Apps.

## Troubleshooting

- **A black screen, or the app says it needs the web view:** the Fire TV's built-in web view is missing or too old. Updating Fire OS (Settings → My Fire TV → About → Check for updates) usually fixes it.
- **Pressing Home still shows Amazon's home:** check that Settings → Options → Open Hearth when Home is pressed says **On**. If it says **Off** and pressing OK opens Accessibility settings, run the `pm grant ... WRITE_SECURE_SETTINGS` line. Some Fire OS updates switch the service off; turn it on again.
- **The page looks too big or too small:** it is laid out for 1920 pixels across and scaled to the screen, first by the web view and, if that does not happen, by Hearth itself (Settings → Options → Show the Debug tab, then the Debug tab shows a "Screen: ..." line saying which one). If it still looks wrong, send me a photo.
- **No pictures in the wallpaper picker:** run the `READ_EXTERNAL_STORAGE` grant, then open the picker again. A USB drive needs an OTG cable and, for some drives, a powered hub.
- **Remove Hearth:** first turn Method 1 off (or run the `pm enable` line if you used Method 2), then uninstall it like any app.

## For developers

- `src/lib/android.ts` is the page side of the bridge (one call: `window.HearthAndroid.call(method, argsJson)`), and `android/app/src/main/java/com/hearth/firetv/Bridge.java` is the app side. `WebHost.java` serves the web build, app icons and pictures from `https://hearth.local/`.
- `npm run build:firetv` builds the page and copies it to `android/app/src/main/assets/web`. `npm test` includes `scripts/test-android.mjs`, which plays the app with a fake bridge.
- The LG code is untouched: every Fire TV branch is behind `isAndroid()`.
