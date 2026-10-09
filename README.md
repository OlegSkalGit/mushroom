# Mushroom — Forager's Offline Navigator

Ultra-lightweight, battery-efficient, and 100% native Android application for mushroom foraging, forest navigation, waypoint marking, and offline vector cartography.

Built on the **native Android SDK (Kotlin)** with high-performance vector rendering and offline routing:
- 🗺️ **Mapsforge Vector Maps (`.map`):** Crisp offline vector maps without raster tile bloat.
- 🚴 **BRouter Offline Navigation (`.rd5`):** Embedded routing engine supporting Car, Foot (hiking), and Bicycle profiles with multi-alternative route proposals.
- 🧭 **Points of Interest (`.poi`):** 22 categories of offline locations (cafes, shops, hospitals, fuel/EV, tourism, transit) with category search and auto-naming waypoints.
- 📏 **Interactive Ruler & Route Builder:** Real-time distance measurement, waypoint drag-and-drop, path insertion, and conversion into GPX tracks.
- 🔬 **AI Mushroom Identifier:** On-device neural network classifier analyzing up to 5 photos (cap, stem, hymenophore, cross-section).
- 📖 **100% Offline Encyclopedia:** Local SQLite database of fungal species with edibility and hymenophore filters.
- 🔋 **Maximum Energy Efficiency:** Dynamic GPS polling interval scaling with hardware motion sensor sleep/wakeup.
- 📱 **Full Background Operation:** Reliable track recording while screen is locked and device is in your pocket.
- 💾 **Persistent Public Storage in `/sdcard/mushroom`:** Maps, POIs, routing data, markers, tracks, and settings persist across reinstallations.

---

## 🚀 Automated APK Build

To compile a signed release APK on any machine without pre-installed Android Studio, use the bundled scripts:

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
3. Sign the release APK with `app/keystore/debug.keystore`.
4. Output the compiled release APK to the project root: `Mushroom_yy.MM.dd_HHmm.apk`.

---

## 🛠️ Architecture and Features

### 1. Offline Mapsforge Vector Map Engine
- Custom native Android `Canvas` renderer utilizing **Mapsforge** vector data.
- **Priority Render Queue:** Center-outward LIFO priority queue renders the visible center of the screen first (~10 ms per tile).
- **Stale Zoom Pruning:** Automatically discards outdated render requests when zooming or panning quickly.
- **Seamless Multi-Tier Fallback:** Overscaling (parent tile scaling up) when zooming in, and dual-depth underscaling (4x and 16x child quadrants) when zooming out.
- **Gesture Controls:** Single-finger pan, continuous pinch-to-zoom, two-finger rotation, and double-tap drag with fixed pivot lever.
- **Download/Update Banner:** Prompts to download or update maps when entering regions lacking offline coverage.

### 2. Map, POI & Navigation Downloads by Country
- Navigation menu $\to$ **🗺️ Maps**.
- Downloads organized strictly by **entire sovereign countries** (195+ countries worldwide).
- Each country bundle downloads:
  - Vector map file (`.map`) into `/sdcard/mushroom/maps/`
  - Points of interest (`.poi`) into `/sdcard/mushroom/poi/`
  - BRouter routing segments (`.rd5` in 5°x5° grid tiles) into `/sdcard/mushroom/navigation/`
- Automatic update detection with `[Ready]` and `[Update]` status badges.
- Unified modal progress dialog with percentage indicator and stop button.

### 3. Points of Interest (Locations)
- Top header location button and menu item **📍 Locations**.
- **22 Official Mapsforge POI Categories:** Cafes/Restaurants, Grocery/Shops, Hospitals/Pharmacies, Gas/EV Chargers, Tourism/Attractions, Accommodation, Sports, Transportation, etc.
- **Dynamic Search Bar:** Rapid filtering of categories by name.
- Selection saved to `/sdcard/mushroom/poi/poi_config.json`.
- Rendered on the vector map at zoom $\ge 13$ (labels at $\ge 15$).
- **Smart Waypoint Auto-Naming:** Placing a mushroom marker within 50 meters of a visible POI automatically fills the marker title with that location's name.

### 4. Interactive Ruler & BRouter Route Planning
- **Ruler Mode:** Activated via the ruler button (tactical cyan `#00E5FF`).
  - Single tap adds measurement nodes with real-time distance badges (segment distance / cumulative total from start).
  - Tapping a node deletes it, drag-and-drop repositions it, and tapping a line segment inserts an intermediate node.
- **Route Mode (`Маршрут`):** Appears when ruler contains $>1$ points.
  - Transport mode selection: **Car / Auto**, **Foot / Hiking**, **Bicycle / Bike**, or Dismiss.
  - BRouter engine calculates up to 3 alternative paths between each pair of consecutive waypoints.
  - Overlapping and intersecting routes split into discrete sub-segments.
  - Unselected suggestions displayed in gray; tapping a segment selects it in bright blue (`#00E5FF`) with a toast describing distance and advantages.
- **Exit & Save:**
  - Exiting ruler mode with $>1$ points presents an action dialog: **Save Track** (includes selected blue BRouter segments and straight ruler spans, with total length in km in the title), **Delete** (clears all points), or **Cancel**.
  - With 0 or 1 point, exits immediately without prompt.

### 5. Mushroom Spot Markers (Waypoints)
- Quick double-tap or long-press on map creates a marker at that location (auto-names if near a POI).
- Floating flag button creates a marker at current real-time GPS coordinates.
- Management in **Markers** menu:
  - Custom title, mushroom type (Porcini, Chanterelle, Boletus, etc.), and palette color.
  - Paste coordinates directly from clipboard (Google Maps, messaging apps).
  - Visibility checkboxes to toggle individual markers on the map.
  - Single tap centers camera on marker; export to standard GPX.

### 6. Track Recording and Analysis
- Floating track button on map: start logging (blue polyline) and stop (red STOP badge).
- GPS noise filtering: records points only when displacement $\ge 2.5$ m and satellite accuracy $\le 35$ m.
- **Tracks List:** Displays track title with distance in km in parentheses (e.g., `Route (4.25 km)`), duration, and point count.
- Checkboxes to toggle track visibility on the map.
- Automatic export and import of standard **GPX** files.

### 7. AI Mushroom Classifier & Offline Encyclopedia
- **Neural Network Classifier:**
  - Multi-photo ensemble inference supporting up to 5 photos (cap, stem, hymenophore, cross-section, habitat).
  - 100% on-device inference using bundled model weights.
  - Tapping a result card opens full details in the Encyclopedia.
- **100% Offline Encyclopedia:**
  - Full local SQLite database (`mushrooms.db` ~538 MB).
  - Search by Ukrainian, English, and scientific Latin names.
  - Combined filters by edibility (🟢 Edible, 🔴 Inedible) and hymenophore structure (🧽 Tubes, 🍂 Gills, 🍄 Other).

### 8. Shared Storage Structure `/sdcard/mushroom`
```
/sdcard/mushroom/
├── maps/             # Mapsforge vector map files (.map)
├── navigation/       # BRouter routing grid segments (.rd5)
├── poi/              # Points of Interest (.poi) and poi_config.json
├── markers/          # Saved spots (markers.db / markers.json)
├── tracks/           # Recorded GPX tracks & tracks.json
├── mushrooms/        # Local encyclopedia database & AI models
├── settings/         # App configuration & preferences
└── logs/             # Diagnostic logs (debug builds)
```
> All files in `/sdcard/mushroom/` persist completely across uninstalls and updates.

### 9. Sync and Auto-Update
- Direct integration with GitHub releases: `https://github.com/OlegSkalGit/mushroom`.
- Version check, background APK download, and update installation via Menu $\to$ **Check for Updates**.

---

## 📜 License

MIT License.
