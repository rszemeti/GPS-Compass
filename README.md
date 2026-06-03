# GPS Compass Display

A cross-platform project for reading, parsing, and displaying **NMEA GPS/heading data**.
Originally built in **Python (Tkinter GUI)** for Windows with Bluetooth/serial GPS receivers, and extended with an **Android app** for mobile use. Primarily intended to work with the PPM 4011 series of professional GPS compasses, but shoudl work with any GPS compass using the Realtime Kinematics Layer (RTK)

![App Screenshot](doc/outdoor.jpg)


---

## 📡 What It Does

This project connects to a GPS receiver (via serial / Bluetooth-serial on Windows, or via Bluetooth on Android), parses common NMEA sentences, and displays **real-time navigation data**:

* Heading (with FIR/running average smoothing, offset correction, and wraparound handling)
* Satellites in view / satellites used
* Fix status, UTC time, altitude
* PDOP/HDOP/VDOP (accuracy metrics)
* Raw NMEA sentence log (Python GUI only)

The focus is on **heading display** for use in radio, navigation, and field applications.

---

## 🖥️ Python GUI (Windows/Linux/macOS)

### Downloads

[Download latest nmea_display.exe](https://github.com/rszemeti/GPS-Compass/releases/latest/download/nmea_display.exe)


The Python desktop version is built with **Tkinter** and **pyserial**.



### Features

* Connect to serial/Bluetooth GPS devices (e.g., Brainboxes BT-Serial adapter)
* Configurable:

  * Port selection and auto-refresh
  * Baud rate (default 38400 N81)
  * FIR filter window size
  * Heading offset (°)
* Two display modes:

  * **Main GUI**: full status grid, heading canvas, NMEA log
  * **Mini window**: compact heading + status display (optionally always-on-top)
* Saves user settings (JSON) for next run

![Python based app](doc/python.png)

### Example NMEA sentences handled

* `$GPGGA` – Fix, satellites, altitude
* `$GPGSA` – Mode, PDOP/HDOP/VDOP
* `$GPZDA` – Date/Time
* `$GPHDT` – Heading

### Running

1. Install dependencies:

   ```sh
   pip install pyserial
   ```
2. Run:

   ```sh
   python nmea_display.py
   ```

### Build as `.exe`

To create a stand-alone executable on Windows:

```sh
pip install pyinstaller
pyinstaller --onefile --noconsole nmea_display.py
```

The binary will be under `dist/nmea_display.exe`.

---

## 📱 Android App

The Android version is a **native Kotlin app** with a simple interface designed for mobile heading display.

### Features

* Connects to paired Bluetooth GPS/heading devices
* Displays:

  * Filtered heading (large text)
  * Satellite count and fix status
  * Basic UTC timestamp
* Minimal UI optimized for full-screen use
* Intended as a lightweight “field display” (no NMEA log window, no mini-window)

![Android app](doc/android.png)

### Building the APK

1. Install [Android Studio](https://developer.android.com/studio).
2. Open the project folder `Android_client/`.
3. Ensure the Android SDK is installed (Platform 34, Build-tools 34.0.0).
4. Build → *Make Project* or run:

   ```sh
   ./gradlew assembleDebug
   ```
5. The APK will be generated at:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Installing

Copy the APK to your device and install manually, or deploy directly via Android Studio.

### Preparing a signed release build

If you plan to install the app on your own phone and share updates with a few other people, use your own release key so future APKs can install as updates without requiring an uninstall first.

1. Create an upload keystore:

   ```sh
   keytool -genkeypair -v -keystore release-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```

2. In `Android_client/`, copy `keystore.properties.example` to `keystore.properties` and fill in the real values:

   ```properties
   storeFile=release-upload.jks
   storePassword=your-store-password
   keyAlias=upload
   keyPassword=your-key-password
   ```

3. Build a signed release APK:

   ```sh
   ./gradlew assembleRelease
   ```

4. The signed APK will be generated at:

   ```
   Android_client/app/build/outputs/apk/release/app-release.apk
   ```

5. To copy that signed APK into the tracked repo release folder for GitHub releases:

   ```sh
   cd Android_client
   ./gradlew copySignedReleaseApk
   ```

   This creates:

   ```
   release/gps-compass-android-1.1.apk
   ```

The real `keystore.properties` file and keystore binaries are excluded by `.gitignore`. Keep the keystore and passwords backed up somewhere safe, because you need the same key to ship future updates to the same installed app.

---

## ⚙️ Requirements

* ** Windows version**: Win7 or later
* **Python version**: 3.8+
* **Android**: API level 24+ (Android 7.0+)
* GPS RTK receiver that outputs standard NMEA sentences over serial/Bluetooth.

---

## 🚀 Use Cases

* Amateur radio projects (beam heading, antenna alignment)
* Marine or vehicle navigation
* GPS compass testing
* Educational use for understanding NMEA data

---

## 📂 Project Structure

```
/Python_client       → Python GUI app
/Android_client      → Android Studio project
```

---

## 📜 License

GNU General Public License v2.0

---

## 🙌 Acknowledgements

* [PySerial](https://pyserial.readthedocs.io/)
* [Tkinter](https://docs.python.org/3/library/tkinter.html)
* [Android Bluetooth API](https://developer.android.com/guide/topics/connectivity/bluetooth)
* [RTK System from Barry AGN](doc/Barry_AGN_notes.pdf)
