# Indoor Navigator

Commercial-style Android version of the Wi-Fi indoor navigation demo.

This folder is separate from `android_wifi_navigation`; the original project was copied as a base and left unchanged.

## What is included

- Full-screen indoor floor map UI
- Floating search and guidance controls
- Bottom route status panel
- Auto / 3F / 4F / 5F floor selection
- Stairs / elevator route mode
- Hidden debug panel for matched AP and RSSI details
- Existing Wi-Fi RSSI positioning, PDR fusion, corridor snapping, and A* routing algorithms

## Core files

- `WifiPositioning.java`: AP coordinate loading, RSSI matching, floor choice, weighted centroid position estimate
- `PdrTracker.java`: heading, step detection, and Wi-Fi/PDR fused movement
- `NavigationGraph.java`: corridor mask parsing, connector search, and A* path routing
- `FloorMapView.java`: map, marker, destination, route, and optional AP debug overlay rendering
- `MainActivity.java`: permissions, Wi-Fi scan lifecycle, guidance state, and commercial-style UI

## Run

Open this folder in Android Studio, sync Gradle, then run on an Android phone.

The app requires Wi-Fi, device location services, and runtime permissions for location, nearby Wi-Fi devices, and activity recognition.
