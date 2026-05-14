# BoltScout

BoltScout is an Android app for monitoring Bolt driver ride-request screens and showing quick pickup/dropoff and verdict information in an overlay.

## Setup

Create a `local.properties` file locally with your Android SDK path and Geoapify key:

```properties
sdk.dir=C:/Users/your-user/AppData/Local/Android/Sdk
GEOAPIFY_KEY=your_key_here
```

`local.properties` is intentionally ignored so local paths and secrets do not get committed.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
