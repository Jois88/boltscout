# BoltScout

BoltScout is an Android app for monitoring Bolt driver ride-request screens. When a ride request appears, it reads the pickup and drop-off addresses, shows them in a small overlay, and opens the route in Google Maps.

No mapping or geocoding API key is required. The app passes the detected addresses directly to Google Maps, so `local.properties` only needs your local Android SDK path.

## Setup

Create a `local.properties` file locally with your Android SDK path:

```properties
sdk.dir=C:/Users/your-user/AppData/Local/Android/Sdk
```

`local.properties` is intentionally ignored so local machine paths do not get committed.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
