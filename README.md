# ClearBG Installer

An Android **phone** app (Android 9 and newer) that installs and updates
**ClearBG Watch** and its free watch face on a Wear OS watch, over Wi-Fi,
using the watch's **Wireless debugging**. By Lippy Robotics Labs.

## What it does
1. **Find** the watch on Wi-Fi (or type its IP address and port).
2. **Pair** with the 6-digit code from the watch's "Pair new device" screen.
3. **Identify** the watch (read-only): maker, model, Wear OS version, and
   which ClearBG Watch versions are installed.
4. **Install or update** ClearBG Watch and its watch face, after the user confirms.
5. **Finish**: remind the user to turn Wireless debugging off.

## Safety rules (in `WatchLink.kt`)
- Installs **only** the two ClearBG Watch packages. Anything else is refused.
- Each packed file must be signed with the **ClearBG Watch key**
  (SHA-256 `44:52:1B:…:B1:BD`), or the installer stops.
- **Never** uninstalls, clears data, changes settings or installs an older version.
- Updates keep the user's login, settings and readings.
- Watch commands are a fixed read-only list. No user-typed commands.
- Never touches Bluetooth. Users are told to keep Bluetooth on.
- **No internet connections**, analytics or ads. It talks only to the watch.

## Files
| File | Purpose |
|---|---|
| `app/.../MainActivity.kt` | The screen: big text, numbered steps, pictures |
| `app/.../WatchFinder.kt` | Listens for Wi-Fi announcements to find the watch |
| `app/.../WatchLink.kt` | Pairing, read-only checks, install/update. The only code that talks to the watch |
| `app/.../PhoneDownloads.kt` | Optional: saves a copy of the watch's current version to Downloads |
| `app/src/main/res/drawable-nodpi/` | Guide pictures and icon |
| `design/` | Sources used to draw the guide pictures |

## Building
- Put the official `glucose-app.apk` and `glucose-watchface.apk` in `bundle/`
  (not committed; they are separate, proprietary programs).
- Release signing uses a separate installer key described in `keystore.properties`
  (not committed). Without it, only debug builds can be made.

## License
The installer uses **Kadb** (Apache-2.0), whose pairing part **spake2-java** is
**GPL-3.0**. The installer's source code is therefore published under **GPL-3.0**.

**ClearBG Watch** (the watch app and watch face) is a separate program, not covered
by this license and is not included in this repository.
Copyright © 2026 Patrick Evan Lippy. All rights reserved.

The **ClearBG Watch** name and water-drop logo identify Lippy Robotics Labs' product.
Please don't use them for other apps.
