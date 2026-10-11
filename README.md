# Mushroom — Forager's Offline Navigator / Офлайн-навігатор грибника

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Android SDK](https://img.shields.io/badge/Android%20SDK-26%2B-green.svg)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

*Ultra-lightweight, battery-efficient, and 100% native Android application for mushroom foraging, wilderness navigation, waypoint marking, offline vector cartography, and address search.*

---

## 🌍 Мова / Language
- [🇺🇦 Українська версія](#-українська-версія)
- [🇬🇧 English Version](#-english-version)

---

# 🇺🇦 Українська версія

## 🌲 Про застосунок

**Mushroom** — це повністю автономний навігаційний комплекс для грибників, туристів та дослідників дикої природи. Застосунок працює на 100% офлайн у лісі без мобільного зв'язку та інтернету, використовуючи векторні карти Mapsforge, маршрутизацію BRouter, власну базу адрес України та країн світу, локальну нейромережу класифікації грибів та повну енциклопедію.

### 🌟 Ключові можливості:
- 🗺️ **Векторні карти Mapsforge (`.map`):** Рендеринг карт безпосередньо з файлів без растрових тайлів, затримок та трафіку.
- 🚴 **Офлайн-маршрутизація BRouter (`.rd5`):** Розрахунок до 3 альтернативних варіантів шляху для автомобіля, пішохода/хайкінгу та велосипеда.
- 📏 **Інтерактивна лінійка та авто-маршрут з 1 точки:** Вимірювання відстаней, перетягування точок, вставка сегментів; **для 1 точки** автоматично підхоплюється поточне місцеположення стрілки користувача (GPS) для миттєвої побудови маршруту до цілі.
- 🏠 **Офлайн-база адрес `addr.db` та миттєвий пошук:** Повнотекстовий пошук FTS5/FTS4 у базі `/sdcard/mushroom/addresses/addr.db` (будинки у мікроградусах, вулиці, населені пункти) з підтримкою до 30 живих підказок та миттєвим збереженням точки.
- 📍 **Точки інтересу POI (`.poi`):** 22 офіційні категорії (лікарні, магазини, кафе, АЗС/EV, туризм, транспорт), кластеризація та автопідстановка назви найближчої POI у маркер.
- 🍄 **Грибні точки та маркери:** Збереження за тапом, прапорцем за поточним GPS, адресою або вставкою координат з буфера; палітра кольорів, фільтри видимості та експорт у GPX.
- 🥾 **Фоновий запис GPS-треків:** Foreground-сервіс із захистом від присипляння Android Doze Mode, фільтрація GPS-шумів, відображення кілометражу в назві `(км)` та імпорт/експорт GPX.
- 📥 **Фонове завантаження без блокування екрана:** Суворий порядок завантаження (1. Версія додатку $\to$ 2. Оглядова карта світу $\to$ 3. Карта країни $\to$ 4. Точки інтересу POI $\to$ 5. Файли маршрутизації $\to$ 6. База адрес (25 регіонів) $\to$ 7. Енциклопедія $\to$ 8. Файли визначника). Для карти країни виділено 50% шкали прогрес-бару із зазначенням кількості регіонів та живого статусу завантаження без блокування карти.
- 🔬 **AI Визначник грибів:** Локальна нейромережа (`model.zip`), що працює автономно на процесорі пристрою та аналізує ансамбль до 5 фотографій.
- 📖 **100% Офлайн Енциклопедія грибів:** Локальна база SQLite (`mushrooms.db` ~538 МБ) з фільтрами за їстівністю та типом гіменофора, детальними описами та смертельними двійниками.
- 💾 **Постійне сховище `/sdcard/mushroom`:** Усі дані, карти, треки, мітки та налаштування повністю зберігаються при оновленнях та перевстановленнях застосунку.

---

## 🚀 Автоматизована збірка APK

Для компіляції підписаного релізного APK без необхідності встановлювати Android Studio скористайтеся готовими скриптами:

### 🪟 Windows:
```cmd
_BUILD_apk_.bat
```

### 🐧 Linux та 🍎 macOS:
```bash
chmod +x _BUILD_apk_.sh
./_BUILD_apk_.sh
```

Скрипти автоматично:
1. Визначають архітектуру та операційну систему.
2. Завантажують портативні **OpenJDK 17**, **Gradle 8.7** та **Android Command-Line Tools** (якщо відсутні).
3. Підписують релізний APK ключем `app/keystore/debug.keystore`.
4. Створюють готовий APK у корені проєкту: `Mushroom_yy.MM.dd_HHmm.apk`.

---

## 🛠️ Архітектура та функціонал

### 1. Векторний рушій карт Mapsforge
- Нативний рендерер Android на базі `Canvas` без растрового кешування.
- Пріоритетна черга рендерингу (LIFO): тайли центру екрана малюються в першу чергу (~10 мс на тайл).
- Багаторівневий фолбек зуму: оверскейлінг та андерскейлінг сусідніх рівнів масштабу запобігають появі порожніх ділянок під час швидкого зумування чи панорамування.
- Повний набір жестів: панорамування одним пальцем, pinch-to-zoom, поворот двома пальцями, дабл-тап драг із фіксованим якорем, створення маркера довгим або подвійним тапом.

### 2. Точки інтересу («Локації» / POI)
- 22 офіційні категорії Mapsforge: Медицина, Харчування, Торгівля, Паливо/АЗС/зарядки електромобілів, Туризм, Проживання, Спорт, Транспорт тощо.
- Пошуковий рядок для миттєвої фільтрації категорій.
- Чекбокси видимості зберігаються у `/sdcard/mushroom/poi/poi_config.json`.
- Кластеризація сусідніх точок у кружечки-бейджі, текстові підписи з зуму z15.
- Автоназва міток: створення точки поряд з POI (до 50 м) автоматично заповнює назву маркера.

### 3. Інтерактивна лінійка та BRouter маршрутизація
- **Режим лінійки:** вмикається кнопкою з лінійкою (бірюзова підсвітка `#00E5FF`). Тап ставить точку з бейджем довжини відрізка та загальної відстані. Тап по точці видаляє її, протяжка переміщує, клік по лінії додає проміжну точку.
- **Маршрут для 1 точки:** кнопка «Маршрут» активна навіть за наявності лише 1 точки на лінійці! При натисканні поточне GPS-положення користувача автоматично підставляється як точка старту, і будується маршрут до обраної точки.
- **Багатоточкові маршрути:** прокладання між усіма сусідніми точками лінійки для автомобіля, пішохода або велосипеда.
- BRouter пропонує до 3 альтернатив на ділянку (сірі лінії). Клік по ділянці обирає її (яскраво-блакитна) та показує спливаючу підказку з кілометражем.
- При виході пропонується: «Зберегти трек» (довжина в км автоматично додається у назву), «Видалити» або «Скасувати».

### 4. Офлайн-база адрес `addr.db` та пошук
- Локальна SQLite база за адресою `/sdcard/mushroom/addresses/addr.db`.
- Повнотекстовий індекс пошуку FTS5 із захисним fallback до FTS4, координати у мікроградусах (таблиці `WITHOUT ROWID`).
- Повний захист при заливці з Overpass API: ротація дзеркал, перевірка вільних слотів `/status`, ліміт пам'яті до 2GB, gzip, просторова прив'язка будинків до населених пунктів (сітка 0.1°), витяг адрес з Mapsforge `.poi`.
- Кнопка «Додати адресу» у меню «Маркери» (під вставкою з буфера): живий пошук до 30 варіантів на льоту, захист від помилок у спливаючих повідомленнях, миттєве відкриття діалогу збереження маркера з координатами та назвою.

### 5. Грибні мітки та керування точками
- Створення маркера тапом по карті, кнопкою прапорця за поточним GPS, кнопкою адреси або вставкою координат із буфера обміну (Google Maps / месенджери).
- Назва, тип гриба, опис, індивідуальний колір маркера з палітри.
- Меню «Маркери»: чекбокси показу, перехід до точки в один клік, видалення та експорт у GPX.
- Збереження у локальній базі `mushroom.db`.

### 6. Запис та аналіз GPS-треків
- Кнопка запису на карті (старт — синя лінія, зупинка — червоний STOP).
- Робота у Foreground Service з постійним сповіщенням; стійкість до Android Doze Mode.
- Фільтрація шумів GPS (зміщення $\ge 2.5$ м, точність $\le 35$ м).
- Меню треків з відображенням кілометражу в назві `(наприклад: Ліс 4.25 км)`, тривалості та кількості точок.
- Повний імпорт та експорт стандартних файлів GPX.

### 7. Послідовне завантаження та прогрес-бар
- Суворий порядок завантаження:
  1. Версія додатку (оновлення APK через GitHub).
  2. Оглядова карта світу (`world.map`).
  3. Карта країни (`.map`).
  4. Файли маршрутизації BRouter (`.rd5`).
  5. База адрес (`addr.db`).
  6. Енциклопедія грибів (`mushrooms.db`).
  7. Модель класифікатора (`model.zip`).
- Діалог налаштування закривається одразу при старті, а тонкий прогрес-бар відображається знизу карти без зайвих вікон чи таймерів.
- Завантаження карт організовано виключно по цілих суверенних країнах (понад 195 країн світу).

### 8. AI Визначник грибів та Енциклопедія
- **AI Класифікатор:** до 5 фотографій одного гриба з камери чи галереї (капелюшок, гіменофор, ніжка, розріз, середовище) для ансамблевого аналізу локальною моделлю (`model.zip` ~60 МБ). Перехід до виду в Енциклопедію в один дотик.
- **Офлайн Енциклопедія:** автономна база SQLite `mushrooms.db` (~538 МБ), пошук за українською, латинською та англійською назвами, комбіновані фільтри за їстівністю (🟢 Їстівні, 🔴 Неїстівні, ☠️ Смертельно отруйні) та гіменофором (🧽 Трубчасті, 🍂 Пластинчасті, 🍄 Інші).

### 9. Структура файлів у `/sdcard/mushroom`
```
/sdcard/mushroom/
├── maps/             # Векторні карти Mapsforge (.map)
├── navigation/       # Сегменти маршрутизації BRouter (.rd5)
├── poi/              # Точки інтересу (.poi) та poi_config.json
├── addresses/        # Офлайн-база адрес (addr.db)
├── markers/          # Збережені грибні точки та markers.db
├── tracks/           # Записані та імпортовані треки GPX
├── mushrooms/        # База енциклопедії mushrooms.db та ваги AI моделі
├── settings/         # Конфігурація та налаштування додатку
└── logs/             # Логи діагностики
```
*Усі файли повністю зберігаються при оновленнях та перевстановленнях додатка.*

---

# 🇬🇧 English Version

## 🌲 About Mushroom

**Mushroom** is an ultra-reliable, battery-efficient, and 100% native offline navigation application designed for mushroom foragers, wilderness hikers, and outdoor explorers. It functions completely without mobile coverage or internet connectivity, powered by offline Mapsforge vector maps, BRouter routing, an embedded spatial address database, an on-device neural network mushroom classifier, and a comprehensive offline fungal encyclopedia.

### 🌟 Core Highlights:
- 🗺️ **Mapsforge Vector Maps (`.map`):** Direct vector rendering without raster tile bloat, network lag, or web caching.
- 🚴 **BRouter Offline Navigation (`.rd5`):** Calculates up to 3 alternative routes per segment for Car, Foot (hiking), and Bicycle profiles.
- 📏 **Interactive Ruler & 1-Point Route Generation:** Distance measurement, node dragging, intermediate waypoint insertion; **supports 1-point routing** by automatically utilizing the user's current GPS location arrow as the origin.
- 🏠 **Offline Address Database `addr.db` & Instant Search:** FTS5/FTS4 full-text search in `/sdcard/mushroom/addresses/addr.db` (microdegree coordinates, `WITHOUT ROWID`, streets, settlements) with real-time suggestion list up to 30 items.
- 📍 **Points of Interest (`.poi`):** 22 official categories (Hospitals, Food, Shops, Fuel/EV, Tourism, Transit), clustering badges, and automatic waypoint naming within 50 m.
- 🍄 **Mushroom Waypoint Markers:** Add by map tap, floating flag at GPS position, offline address search, or clipboard coordinate pasting; custom color palette, visibility toggles, and GPX export.
- 🥾 **Background GPS Track Logging:** Ongoing Foreground Service immune to Android Doze Mode throttling, GPS noise filtration, distance in title `(km)`, and GPX import/export.
- 📥 **Non-Blocking Background Downloads:** Strict priority sequence (1. App version $\to$ 2. World map $\to$ 3. Country map $\to$ 4. Points of Interest (POI) $\to$ 5. Routing files $\to$ 6. Address database (25 regions) $\to$ 7. Encyclopedia $\to$ 8. Classifier files). Country map is allocated 50% of the progress bar scale, displaying region count and real-time status at the bottom of the map without blocking screen interaction.
- 🔬 **AI Neural Network Mushroom Identifier:** On-device neural classifier (`model.zip` ~60 MB) analyzing up to 5 photos (cap, stem, hymenophore, cut, habitat).
- 📖 **100% Offline Mushroom Encyclopedia:** Local SQLite database (`mushrooms.db` ~538 MB) with edibility and hymenophore filters, morphological profiles, and poisonous lookalike warnings.
- 💾 **Persistent Public Storage in `/sdcard/mushroom`:** Maps, POIs, routing tiles, waypoints, tracks, and settings survive app reinstallation and updates.

---

## 🚀 Automated APK Build

Compile a signed release APK on any machine without Android Studio:

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
1. Detect system architecture and OS.
2. Download portable **OpenJDK 17**, **Gradle 8.7**, and **Android Command-Line Tools** (if missing).
3. Sign the release APK with `app/keystore/debug.keystore`.
4. Output the compiled APK to the root folder: `Mushroom_yy.MM.dd_HHmm.apk`.

---

## 🛠️ Architecture and Capabilities

### 1. Offline Mapsforge Vector Engine
- Custom Android `Canvas` renderer utilizing native **Mapsforge** vector data.
- **Priority Render Queue (LIFO):** Center-outward queue renders the visible viewport first (~10 ms per tile).
- **Multi-Tier Zoom Fallback:** Dynamic overscaling and underscaling eliminate blank screens during fast zooms.
- Full gesture set: single-finger panning, continuous pinch-to-zoom, two-finger rotation, double-tap drag with fixed pivot lever, and waypoint creation via double-tap or long-press.

### 2. Points of Interest (Locations / POI)
- 22 official Mapsforge categories: Cafes, Shops, Hospitals, Gas/EV chargers, Tourism, Accommodation, Sports, Transit, etc.
- Search bar for instantaneous category filtering.
- Visibility preferences persist in `/sdcard/mushroom/poi/poi_config.json`.
- Cluster badges for adjacent points; street names and POI labels render at zoom $\ge 15$.
- Smart Auto-Naming: placing a waypoint within 50 meters of a visible POI auto-populates the marker title with the POI name.

### 3. Interactive Ruler & BRouter Route Planning
- **Ruler Mode:** Activated via ruler button (cyan `#00E5FF`). Single tap places measurement nodes with segment length and cumulative distance badges. Tap node to delete, drag to move, tap line to insert node.
- **1-Point Route Mode:** The 'Route' button is active even with a single node on the ruler! Clicking it automatically snaps the user's real-time GPS coordinates as point #1 and routes to the target point.
- **Multi-Point Routing:** Generates paths between consecutive nodes for Car, Foot, or Bicycle.
- Displays up to 3 alternatives per span in gray; tapping a segment selects it in cyan-blue with distance details.
- Exiting with $>1$ points prompts: Save Track (length in km appended to title), Delete, or Cancel.

### 4. Offline Address Database `addr.db` & Search
- Local SQLite database located at `/sdcard/mushroom/addresses/addr.db`.
- Full-text search index FTS5 with safe fallback to FTS4, microdegree coordinates (`WITHOUT ROWID`).
- High-reliability Overpass ingestion: mirror rotation with `/status` slot checking, 2GB memory limit escalation, gzip compression, 0.1° spatial settlement index, and Mapsforge `.poi` address extraction.
- 'Add Address' button in Markers menu: live debounced search returning up to 30 suggestions, system error toasts, and direct transition to waypoint creation with pre-filled title and coordinates.

### 5. Waypoints & Marker Management
- Create waypoints via map gestures, floating flag button (GPS position), Add Address dialog, or clipboard coordinates (Google Maps / messengers).
- Title, mushroom type, notes, and individual palette color.
- Markers menu: visibility checkboxes, one-click camera centering, editing, and GPX export.
- Stored locally in SQLite database `mushroom.db`.

### 6. Track Recording & Analysis
- Map recording button (start: blue polyline, stop: red STOP square).
- Foreground Service with persistent notification; resistant to Android Doze Mode.
- GPS noise filtration (displacement $\ge 2.5$ m, accuracy $\le 35$ m).
- Tracks menu with total distance in km in title `(e.g. Forest Path (4.25 km))`, duration, and point count.
- Full GPX file import and export.

### 7. Priority Download Sequence & Minimalist Progress Bar
- Strict 7-step sequence:
  1. App Version (APK update via GitHub).
  2. World Overview Map (`world.map`).
  3. Country Vector Map (`.map`).
  4. BRouter Routing Segments (`.rd5`).
  5. Address Database (`addr.db`).
  6. Mushroom Encyclopedia (`mushrooms.db`).
  7. AI Classifier Model (`model.zip`).
- Setup dialog closes immediately when download begins; progress is displayed via a slim, non-intrusive progress bar at the bottom of the map screen without blocking user actions.
- Country downloads organized strictly by entire sovereign nations (195+ countries worldwide).

### 8. AI Mushroom Identifier & Offline Encyclopedia
- **AI Classifier:** Supports up to 5 photos from camera or gallery (cap, hymenophore, stem, cut, habitat) for ensemble classification by on-device neural network (`model.zip` ~60 MB). Direct link from result card to encyclopedia.
- **Offline Encyclopedia:** SQLite database `mushrooms.db` (~538 MB) with multilingual search (Ukrainian, Latin, English), edibility filters (🟢 Edible, 🔴 Inedible, ☠️ Deadly Poisonous), and hymenophore filters (🧽 Pores, 🍂 Gills, 🍄 Other).

### 9. Shared Storage Structure `/sdcard/mushroom`
```
/sdcard/mushroom/
├── maps/             # Mapsforge vector map files (.map)
├── navigation/       # BRouter routing grid segments (.rd5)
├── poi/              # Points of Interest (.poi) and poi_config.json
├── addresses/        # Offline address database (addr.db)
├── markers/          # Saved waypoint markers (markers.db / mushroom.db)
├── tracks/           # Recorded and imported GPX tracks
├── mushrooms/        # Local encyclopedia database (mushrooms.db) & AI models
├── settings/         # App configuration & preferences
└── logs/             # Diagnostic logs (debug builds)
```
*All files in `/sdcard/mushroom/` strictly persist across application updates and uninstalls.*

---

## 📜 License

Distributed under the [MIT License](LICENSE).
