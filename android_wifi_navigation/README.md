# WifiNavigation Android

Android version of the Wi-Fi indoor positioning demo.

Open this folder in Android Studio, let Gradle sync, then run it on an Android phone.

The app uses:

- `WifiManager` scan results for nearby AP RSSI values
- `app/src/main/assets/ap_coordinates.csv` for AP map coordinates
- `app/src/main/assets/3fmap.png`, `4fmap.png`, `5fmap.png` for floor maps
- the same weighted-centroid positioning idea as `scan.py`

Notes:

- Wi-Fi scanning requires runtime permissions.
- On many Android versions, location services must be enabled for Wi-Fi scan results.
- Android throttles Wi-Fi scans, so the actual refresh rate may be slower than the app's request interval.
- This folder does not include a Gradle wrapper. Android Studio can create/use one after opening the project, or you can install Gradle separately.

First run checklist:

1. Open `android_wifi_navigation` in Android Studio.
2. Sync Gradle.
3. Connect an Android phone.
4. Run the app.
5. Allow nearby Wi-Fi/location permissions.
6. Turn on Wi-Fi and device location services.
