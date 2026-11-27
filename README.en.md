[中文](README.md) | [English](README.en.md)

# AvesTV Push (Zero-Modification Companion for Stock Aves)

A local-first dual-device Android app suite: **the phone app automatically discovers your Android TV over LAN or USB with zero manual IP setup** and pushes selected photos straight into the TV's system `MediaStore` (`Pictures/TVAlbums/<AlbumName>/`). Install the **stock Aves Gallery** on your TV (zero code changes required) and it will immediately detect new albums for slideshows or Android TV screensavers.

## Key Features

- **Zero Modification to Stock Aves**: Aves observes Android's system `MediaStore`. The TV receiver uses atomic `ContentResolver` writes (`IS_PENDING`) and deletes, so new, overwritten, or deleted photos notify Aves immediately with no broken thumbnails or `IMG_0001 (1).jpg` duplicates.
- **Zero-Config LAN Discovery + Manual IP Fallback**: The phone runs a three-layer concurrent scan on launch, plus a manual IP connect bar:
  1. Instant cached TV IP probe (`< 50ms`);
  2. UDP subnet broadcast (`53318`) + `NsdManager` (mDNS `_avestv._tcp.`);
  3. Automatic `/24` subnet HTTP sweep after 320ms (`< 1s`) when routers block broadcast/multicast (or when TV uses Ethernet and phone uses Wi-Fi).
- **Works Without Wi-Fi — Dual USB Connectivity**:
  - **Phone-to-TV Direct USB (AOA 2.0)**: The TV acts as a USB Host, performs an Android Open Accessory 2.0 handshake, and tunnels the exact same HTTP API over a multiplexed USB stream—no Wi-Fi, no tethering, and no USB debugging required.
  - **USB Tethering (RNDIS)** interface prioritization.
  - **ADB USB Tunnel for Desktop Debugging**: The desktop preview server includes a built-in `AdbUsbWatcher` that automatically maps `adb reverse tcp:53319` whenever a debug phone is plugged in.
- **Always-On Background TV Receiver**: Uses an Android 14/15-compliant `specialUse` foreground service + boot receiver so photos arrive silently even while Aves is playing fullscreen.
- **No `MANAGE_EXTERNAL_STORAGE` Needed**: On Android 10+, photos are written directly as app-owned `MediaStore` items with zero intrusive storage permission prompts.
- **4K JPEG Compatibility Optimization**: Reads EXIF orientation and converts oversized or `.heic` photos into upright 4K (3840px long edge) JPEGs by default to ensure smooth decoding on older TVs while saving flash storage (can be toggled off for originals).
- **TV Album Management & Multi-Select Batch Delete**: Preview TV albums on your phone, switch between append (overwrite by filename) and replace-album modes, delete single photos, **multi-select / select-all batch delete**, or clear an entire album (phone originals are never touched).
- **Persistent Debug Logs Across Both Devices**: Both TV and phone keep a 500-entry ring buffer + rolling log file (`512KB` rotation). The phone UI includes a built-in log viewer to inspect, copy, or clear **both TV logs (`GET/DELETE /api/v1/logs`) and phone logs**.

## Project Structure

- `protocol/`: Shared models, discovery codec, `/24` subnet helpers, zero-dependency streaming HTTP/1.1 server, UDP responder, multiplexed USB HTTP tunnel (`UsbHttpTunnel`), persistent `DebugLogStore`, and desktop `PreviewServer`.
- `web/`: Shared responsive web UI (`phone.html`, `tv.html`, `app.css`, `api.js`) used by both desktop browsers and the Android WebViews.
- `tv/`: Android TV receiver APK (`com.avestv.tv`) with boot-capable foreground service, `UsbAoaHostManager`, and `MediaStore` repository.
- `phone/`: Android phone sender APK (`com.avestv.phone`) with LAN/USB discovery, share sheet target, Photo Picker (with `OPEN_DOCUMENT` / `GET_CONTENT` fallback), `UsbAoaAccessoryManager`, and 4K transcoder.
- `scripts/`: Desktop USB preview helper (`run-usb-preview.sh`).
- `docs/`: Developer & debugging guide ([debug-guide.md](docs/debug-guide.md)).

## Download APKs (GitHub Releases)

Prebuilt APKs are no longer committed under `dist/`. Signed Release APKs are published in [**GitHub Releases**](https://github.com/jmwasky-creative/aves-tv/releases):

- TV Receiver: `tv-release-v0.1.apk` (App name: **电视相册接收器**)
- Phone Sender: `phone-release-v0.1.apk` (App name: **电视相册推送**)

## Build from Source

Requires JDK 17+ and Android SDK (API 35):

```bash
# Run unit tests and build Release APKs
./gradlew :protocol:test :phone:testDebugUnitTest assembleRelease

# Or build Debug APKs (debug-signed, ready to install)
./gradlew assembleDebug
```

Signing: Keystores are not stored in the repository. Copy `keystore.properties.example` to `keystore.properties` and fill in your local keystore path and passwords to produce signed Release APKs; otherwise `assembleRelease` outputs unsigned APKs.

Build outputs:
- TV Release: `tv/build/outputs/apk/release/tv-release.apk`
- Phone Release: `phone/build/outputs/apk/release/phone-release.apk`
- Debug APKs: `tv/build/outputs/apk/debug/tv-debug.apk` & `phone/build/outputs/apk/debug/phone-debug.apk`

## Desktop UI Preview & USB Debugging

```bash
./gradlew :protocol:run
```

Open the printed `phone.html` and `tv.html` URLs in your browser (default port `53319`):
- Built-in `AdbUsbWatcher` automatically sets up `adb reverse tcp:53319` when a USB-debugging phone is connected, allowing Wi-Fi-free testing against the desktop mock TV.
- Both pages share the same in-memory album store and live debug logs.
- See [docs/debug-guide.md](docs/debug-guide.md) for ADB, WebView, and LAN troubleshooting details.

## Installation & Usage

### 1. TV Setup (One-Time)
1. Install stock **Aves** on your Android TV (from F-Droid or GitHub Releases, v1.15+ recommended).
2. Install `tv-release-v0.1.apk` from GitHub Releases.
3. Open **电视相册接收器** once with the TV remote and confirm the top-right badge shows `运行中 · 自动可发现` (grant storage permission on Android 8/9; zero prompts on Android 10+).
4. Disable battery optimization for the receiver app in TV system settings so it stays alive in the background.
5. Tap **打开原版 Aves 播放** to open Aves and start a slideshow on `TVAlbums/<AlbumName>`, or bind it as your Android TV screensaver.

### 2. Sending Photos from Your Phone
1. Install `phone-release-v0.1.apk` from GitHub Releases.
2. Connect using either method:
   - **Wi-Fi LAN**: Keep phone and TV on the same non-isolated LAN; the app connects automatically (or use **手动连 IP**).
   - **Direct USB (No Wi-Fi needed)**: Plug your unlocked phone into the TV's USB port with a data cable and accept the USB permission prompts.
3. Choose or type an album name and tap **选择照片并推送到电视**, or share photos directly from your system gallery to **推到电视相册**.
4. Switch between append/replace modes, or use single-delete / **multi-select batch delete** / clear-album in the bottom grid.
