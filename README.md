# Mushroom — Forager's Offline Navigator

Ultra-lightweight, battery-efficient, and 100% native Android application for mushroom foraging, forest navigation, marking spots, and working with offline OpenStreetMap (OSM) maps.

Built exclusively on the **native Android SDK (Kotlin)** without heavy third-party libraries (Google Play Services, Mapbox, Osmdroid, Room, etc.):
- ⚡ **APK Size:** **~134 KB** (strict limit < 1 MB).
- 🧠 **RAM Usage:** **< 20 MB** (efficient LruCache tile caching).
- 🔋 **Maximum Energy Efficiency:** WakeLock is held only during active track recording. GPS polling interval scales dynamically when stationary, transitioning into deep sleep with hardware motion sensor wakeup during extended stops.
- 📱 **Full Background Operation:** Tracks are reliably recorded even when the screen is locked and the device is in your pocket.
- 💾 **Persistent Storage in `/sdcard/mushroom`:** Settings, markers, tracks, and maps are stored in a public user folder and are never deleted when reinstalling the app.

---

## 🚀 Automated APK Build

To quickly compile a signed release APK on any computer, use the provided build scripts:

### 🪟 Windows:
```cmd
_BUILD_apk_.bat
```

### 🐧 Linux & 🍎 macOS:
```bash
chmod +x _BUILD_apk_.sh
./_BUILD_apk_.sh
```

The scripts automatically:
1. Detect architecture and host OS.
2. Download portable **OpenJDK 17**, **Gradle 8.7**, and **Android Command-Line Tools** if missing.
3. Sign the release APK with `app/keystore/debug.keystore` (ensuring consistent digital signature for seamless updates).
4. Copy the compiled release APK to the project root:
   `Mushroom_yy.MM.dd_HHmm.apk` (~134 KB).

---

## 🛠️ Architecture and Features

### 1. Offline OSM Maps (100% Native Canvas)
- Custom native **Slippy Map** engine powered by Android `Canvas`.
- Smooth panning and pinch-to-zoom gestures.
- Action buttons for quick zoom, adding markers, recording tracks, and centering on current position (`📍 Center`).
- Multi-tier caching: RAM LruCache + disk tile persistence in `/sdcard/mushroom/maps/tiles/{z}/{x}/{y}.png`.
- Full autonomy in dense forests without cellular connectivity.

### 2. Map Downloads by Country and Region
- Menu $\to$ **Offline Maps**.
- Select country (Ukraine, Poland, etc.).
- Region list with **checkboxes** for multi-region batch downloads.
- Estimated tile count calculation for zoom levels z10..z13.
- Background downloading with progress dialog and cancel support.

### 3. Mushroom Spot Markers (Waypoints)
- **Add Marker** button: saves coordinates with custom name, color, type (Porcini, Chanterelle, Boletus, Honey agaric, Suillus, Car/Base, etc.), and optional notes.
- **Markers List**:
  - Displays precise distance (in meters/km) and compass direction (N, NE, E, SE, S, SW, W, NW) from your current position.
  - **Checkboxes**: toggle visibility of individual markers on the map.
  - Tapping a marker centers the map on that point.
  - Persisted in SQLite database and synchronized to `/sdcard/mushroom/markers/markers.json`.
  - Long press on marker icon on map prompts for deletion.

### 4. Track Recording and Export
- **Record Track** button: start/stop background recording of your foraging path.
- GPS noise filtering: points are recorded only when displacement $\ge 2.5$ m and satellite accuracy $\le 35$ m (eliminating GPS drift webs while stationary).
- Visual rendering of the active track with custom color.
- **Tracks List**:
  - Displays distance (km), duration, and point count.
  - **Checkboxes**: toggle track visibility on the map.
  - Clicking a track centers and zooms the map to fit its bounding box.
  - Automatic export to standard **GPX** format in `/sdcard/mushroom/tracks/`.

### 5. Positioning, Compass, and Orientation
- Current GPS location centered on the screen.
- **Hardware Compass**: smooth compass rose with azimuth in degrees.
- **Heading Indicator**: dynamic walking direction arrow.
- Orientation modes: **North Up** (fixed map) or **Course Up / Compass Follow** (map rotates with device). Dedicated North-align button.
- Top dashboard: coordinates, altitude, GPS accuracy, and walking speed.

### 6. Shared Storage `/sdcard/mushroom`
Directory tree on device:
```
/sdcard/mushroom/
├── maps/
│   └── tiles/{z}/{x}/{y}.png    # Offline map tile cache
├── markers/
│   └── markers.json              # Saved foraging markers
├── tracks/
│   ├── tracks.json               # Track metadata list
│   └── track_<id>.gpx            # Standard GPX files
├── settings/
│   └── settings.json             # Settings backup
└── logs/                         # Diagnostic logs (debug builds)
```
> When uninstalling or updating the app, `/sdcard/mushroom` is preserved completely. Upon reinstallation, all maps, markers, and tracks load automatically!

### 7. Sync and Auto-Update
- Direct integration with GitHub releases:
  `https://github.com/OlegSkalGit/mushroom`
- Release check, background APK download, and one-click update via Menu $\to$ **Check for Updates**.

---

## 📜 License

MIT License.
