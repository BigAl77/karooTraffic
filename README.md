# KarooTraffic (`mybiketraffic` for Hammerhead Karoo)

**KarooTraffic** is a Hammerhead Karoo extension (`karoo-ext`) port of the open-source Garmin ConnectIQ [`mybiketraffic`](https://github.com/kartoone/mybiketraffic) app. It pairs with rear-facing cycling radars (such as the **Garmin Varia** or **Trek CarBack / RearVue**) to count passing vehicles, track passing speeds and closest target distance in real time on your Karoo screen, write developer data fields directly into your ride `.fit` file, and **automatically upload your completed rides over Wi-Fi to [MyBikeTraffic.com](https://www.mybiketraffic.com/)**.

---

## Features

- **Garmin-Parity Multi-Metric Data Field (`MyBikeTraffic`)**:
  - Configurable **1 to 6 metrics** displayed in a single Karoo data field (auto-adapting single-column or 2-column split layout).
  - Supports **Count**, **Closest Distance**, **Relative Speed**, **Absolute Speed**, **Last Passing Speed**, **Lap Count**, **Radar Connected Status (`+`/`-`)**, and **Disabled**.
  - Respects your Karoo's **Imperial (mph)** or **Metric (km/h)** unit preference and Day/Night theme.
  - **Double-tap** the data field during a ride to reset the radar connection if the sensor drops out.
- **Automatic Post-Ride Upload to MyBikeTraffic.com (Over Wi-Fi)**:
  - Sign into your **MyBikeTraffic.com** account once inside the **KarooTraffic** app on your Karoo.
  - Whenever you finish and save a ride (`RideState.Idle`), **KarooTraffic** automatically detects your newest `.fit` file in `/sdcard/FitFiles/`, waits for your Karoo to connect to Wi-Fi, and uploads the `.fit` file directly to `https://www.mybiketraffic.com/rides/import`.
  - Also includes an **"Upload Latest .FIT Now"** button and an in-app browser with 1-tap **"Attach Latest .FIT"**.
- **Dual Radar Connection Modes**:
  - **Built-in Karoo Radar Stream (Default)**: Uses Karoo's native radar stream (`DataType.Type.RADAR`) so it works seamlessly alongside Karoo's native visual radar strip and audio beeps.
  - **Switch between Built-in & Direct ANT+** or **Direct ANT+ / RearVue 820 Only** (`Device Type 40`).
- **Full `.fit` File Developer Field Recording**:
  - Writes the exact same developer fields (`0` through `6`) as the Garmin ConnectIQ app into your Karoo ride `.fit` file:
    - `radar_total` (`uint16`, Session & Record) — Cumulative vehicle count
    - `radar_lap` (`uint16`, Lap) — Vehicles counted in the current lap
    - `radar_ranges` (`uint16[8]`, Record) — Target distances in meters
    - `radar_speeds` (`uint8[8]`, Record) — Approach speeds in m/s
    - `radar_current` (`uint16`, Record) — Vehicle number assigned to current passing event
    - `passing_speed` (`uint8`, Record) — Relative passing speed
    - `passing_speedabs` (`uint8`, Record) — Absolute vehicle speed (relative speed + rider speed)
- **Built-in Interactive Ride & Traffic Simulator**:
  - Test layouts, vehicle approaches, multi-car queues, and passing counts locally on your PC (Windows Android Emulator) or directly on the Karoo without needing to ride in traffic.

---

## 1. Installing KarooTraffic on Your Karoo 2 / Karoo 3

### Option A: Wireless Sideload via Hammerhead Companion App (Karoo 3 / Latest Karoo 2 Firmware)
1. Download `karooTraffic-debug.apk` from the [latest GitHub Release](https://github.com/BigAl77/karooTraffic/releases) (or build it from `app/build/outputs/apk/debug/karooTraffic-debug.apk`) onto your phone.
2. Share the `.apk` file to the **Hammerhead Companion App** on your phone to install it wirelessly onto your Karoo.

### Option B: Install via USB-C (`adb`)
1. **Enable Developer Options & USB Debugging on your Karoo:**
   - Open **Settings** -> scroll down to **About** -> tap **Build Number** **7 times** until you see *"You are now a developer!"*.
   - Go back one screen to **Settings** -> **Developer Options** (or **System** -> **Developer Options**) and toggle **USB Debugging** to **ON**.
2. **Connect & Run Installer:**
   - Plug your Karoo into your computer using a data-capable USB-C cable and tap **Allow** on the *"Allow USB debugging?"* prompt on the Karoo screen.
   - From the repository folder in PowerShell, run:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\install-to-karoo.ps1
     ```
     *(Or using `adb` directly: `adb install -r app/build/outputs/apk/debug/karooTraffic-debug.apk`)*

---

## 2. Setting Up Automatic Wi-Fi Upload to MyBikeTraffic.com

1. Open **KarooTraffic** on your Karoo (make sure Wi-Fi is connected).
2. When prompted for **Storage Permission** (`Allow KarooTraffic to access photos, media, and files on your device?`), tap **Allow** — this lets KarooTraffic read `/sdcard/FitFiles/` when your ride finishes.
3. In the **MyBikeTraffic.com Cloud Auto-Upload (Wi-Fi)** card:
   - Make sure **"Auto-upload ride .FIT to MyBikeTraffic.com when ride finishes (over Wi-Fi)"** is checked (`ON`).
   - Tap **"Sign In / Open MyBikeTraffic"**.
   - Sign into your MyBikeTraffic.com account once in the built-in browser, then tap **"Done / Back"** in the top-left corner.
4. **How it works after every ride:**
   - When you end and save a ride on your Karoo, `KarooTraffic` waits ~6 seconds for Karoo OS to finish saving the `.fit` file to `/sdcard/FitFiles/`.
   - If your Karoo is already on Wi-Fi, it uploads the `.fit` file immediately to `https://www.mybiketraffic.com/rides/import`.
   - If you save your ride outside before Wi-Fi is in range, `KarooTraffic` queues the ride (`Waiting for Wi-Fi connection...`) and automatically uploads it as soon as your Karoo connects to your home Wi-Fi!
   - You can also tap **"Upload Latest .FIT Now"** anytime in the app (or tap **"Attach Latest .FIT"** inside the built-in MyBikeTraffic browser).

---

## 3. Setting Up & Using the Data Field on Your Karoo

### Step 1: Pair Your Garmin Varia Radar
1. On your Karoo, open **Sensors**.
2. Turn on your Garmin Varia radar and make sure it is paired and connected (via ANT+).

### Step 2: Configure Your Display & Radar Settings
1. Open **KarooTraffic** on your Karoo.
2. Configure your preferences:
   - **Sim Radar On**: Make sure this is **unchecked (`OFF`)** for real outdoor rides!
   - **Radar connection mode**: Leave on **Built-in Karoo Radar Stream (Default)** (or **Switch between Built-in & Direct ANT+**) so Karoo's native left-side radar bar and audio alerts continue working alongside `KarooTraffic`.
   - **Display positions (1–6)**: Choose which metrics you want displayed in each slot, and set unused metrics to **Off (0)**.

### Step 3: Add the `MyBikeTraffic` Data Field to Your Ride Profile
1. On your Karoo home screen, tap **Profiles**.
2. Select your ride profile (e.g., *Road*) and tap **Data Pages**.
3. Edit an existing page or add a new page.
   - **Tip**: Because `MyBikeTraffic` can render up to 6 sub-metrics inside a single field, it looks best in a **larger slot** (such as a 1-field, 2-field, or 3-field page layout).
4. Tap the data field slot where you want traffic data -> scroll down to **Extensions** (or **KarooTraffic**) -> select **MyBikeTraffic**.
5. Save your profile and ride!

---

## 4. Manual `.fit` File Extraction & Upload (Fallback Options)

If you ever want to manually copy `.fit` files off your Karoo onto your computer and upload them from your PC browser:

### Getting `.fit` Files Off the Karoo
- **Option A — One-Command USB Pull Script:**
  Plug your Karoo into your PC via USB-C and run:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\pull-fit-files.ps1
  ```
  This pulls all `.fit` files from `/sdcard/FitFiles/` on your Karoo into **`Desktop\KarooFitFiles`** on your PC.
- **Option B — Hammerhead Web Dashboard:**
  Log into **[https://dashboard.hammerhead.io](https://dashboard.hammerhead.io)** -> open your ride -> click the menu (`...`) -> select **Download FIT**.
- **Option C — Windows File Explorer (USB File Transfer / MTP):**
  Plug in via USB-C -> swipe down the notification shade on the Karoo -> change USB mode to **File Transfer** -> copy `.fit` files from `Karoo 2\Internal shared storage\FitFiles`.

### Manual Web Upload to MyBikeTraffic.com
1. Open **[https://www.mybiketraffic.com/rides/import](https://www.mybiketraffic.com/rides/import)** in your browser.
2. Click **Choose File** / **Browse** and select your `.fit` file.
3. Click **Upload / Import** to view your vehicle counts, passing speeds, and interactive traffic map.

---

## 5. Local Development & Emulator Testing

### Running on the Windows Android Emulator ($480 \times 800$ Karoo 2 Screen)
To launch a local Karoo 2 Android Emulator on Windows, install the latest APK, and open the built-in interactive Ride & Traffic Simulator:
```powershell
powershell -ExecutionPolicy Bypass -File .\run-emulator.ps1
```

### Building & Running Unit Tests
Requires **JDK 21** and the **Android SDK (API 34)**. Also make sure [`karoo-ext`](https://github.com/hammerheadnav/karoo-ext) is published to `mavenLocal()` (or configured via GitHub Packages):
```bash
./gradlew testDebugUnitTest assembleDebug
```
The compiled APK is output to `app/build/outputs/apk/debug/karooTraffic-debug.apk`.

---

## License & Notices

Licensed under the **Apache License, Version 2.0**. See [`LICENSE`](LICENSE) and [`NOTICES.txt`](NOTICES.txt) for full license text, third-party attributions, and trademark disclaimers.
