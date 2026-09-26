# cobbvision-android

CobbVision Companion for Android — a GPS track recorder for Cobb Accessport datalog sessions.

## What it does

The Cobb Accessport logs engine data (`datalog*.csv`) while you drive, but those logs
carry no position. This app runs on the phone in the car, records a GPS track for the
same drive, and uploads it to the CobbVision backend, which matches the track to the
Accessport datalog session by time so logs can be viewed against location, speed and
route.

The app does **not** read Accessport datalog files itself. It only produces GPS data:

- **Records** fused-location fixes (latitude, longitude, altitude, speed, horizontal
  accuracy, timestamp) at 1 Hz with `PRIORITY_HIGH_ACCURACY`, from a foreground service
  (`LocationService`) so recording continues while the app is in the background. A
  persistent notification with a *Stop* action is shown while recording.
- **Filters** out fixes whose horizontal accuracy is worse than 30 m (or negative).
- **Stores** completed sessions locally as JSON (`filesDir/sessions.json`,
  `kotlinx.serialization`) so they can be uploaded later from the History screen.
- **Exports** each session as a GPX 1.1 file in the app cache directory (see
  [Export format](#export-format)).
- **Uploads** the GPX to `POST /api/v1/gps-track` on the CobbVision server as
  `multipart/form-data` with a `vehicle_id` field and the `gpx` file part
  (`application/gpx+xml`), authenticated with `Authorization: Bearer <api key>`.
- **Lists vehicles** from `GET /api/v1/vehicles` (each vehicle carries its Accessport
  serial, `ap_serial`) so a track can be attached to the right car.

The server URL and API key are entered on the Settings screen (persisted with
DataStore preferences); the key comes from the Account page of the CobbVision web app.

## Source layout

```
app/src/main/java/com/cobbvision/companion/
  CobbVisionApp.kt, MainActivity.kt    Application / single-activity Compose host
  data/TrackPoint.kt                   One GPS sample (@Serializable)
  data/DriveSession.kt                 A recorded session (@Serializable)
  data/SessionRepository.kt            JSON persistence of completed sessions
  service/LocationService.kt           Foreground service driving FusedLocationProviderClient
  service/TrackRecorder.kt             Pure accuracy filter + accumulation (unit-tested)
  service/GPXExporter.kt               DriveSession -> GPX 1.1 file
  network/ApiClient.kt                 CobbVision REST client (vehicles, GPS track upload)
  ui/                                  Compose navigation, screens and theme
app/src/test/java/...                  JUnit unit tests (plain JVM, no emulator)
app/src/main/AndroidManifest.xml       Permissions, foreground service declaration
gradle/libs.versions.toml              Version catalog
```

## Building

Toolchain (from `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`
and `app/build.gradle.kts`):

| Component | Version |
| --- | --- |
| JDK | 17 (`sourceCompatibility`/`jvmTarget` 17; CI uses Temurin 17) |
| Gradle | 8.7 (wrapper) |
| Android Gradle Plugin | 8.3.2 |
| Kotlin | 2.0.0 (+ Compose compiler plugin, `kotlinx.serialization`) |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| Compose BOM | 2024.06.00 (Material 3) |
| Play Services Location | 21.3.0 |

The Gradle wrapper scripts (`gradlew`, `gradle-wrapper.jar`) are not committed; generate
them once with a locally installed Gradle, and point the build at your Android SDK:

```sh
gradle wrapper --gradle-version 8.7
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests
./gradlew lint                 # Android lint
```

Or open the project in Android Studio (Koala or newer for AGP 8.3 / Kotlin 2.0).
CI (`.github/workflows/build.yml`) generates the wrapper, runs the unit tests, assembles
the debug APK, runs lint (non-blocking) and uploads the APK as a workflow artifact.

### Tests

Unit tests live in `app/src/test/` and run on the JVM without an emulator
(`./gradlew test`). They cover track recording (`TrackRecorder`: accuracy filtering and
accumulation into a `DriveSession`, speed clamping) and GPX export (`GPXExporter`:
structure, field formatting, empty and single-point tracks, file naming).
`testOptions.unitTests.isReturnDefaultValues = true` is set so `android.*` classes that
happen to be referenced do not throw in JVM tests.

## Permissions

Declared in `app/src/main/AndroidManifest.xml`:

| Permission | Purpose |
| --- | --- |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS fixes for the track. |
| `ACCESS_BACKGROUND_LOCATION` | Keep recording while the app is not in the foreground (API 29+). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Run `LocationService` as a `location` foreground service (API 34+ type). |
| `INTERNET` | Upload tracks and fetch vehicles from the CobbVision server. |
| `WAKE_LOCK` | Keep the CPU awake during recording. |

`<uses-feature android.hardware.location.gps required="true" />` restricts installs to
devices with GPS. The Session screen requests fine location first and then background
location at runtime before recording starts.

## Export format

`GPXExporter` produces a GPX 1.1 document. Speed is written with the Garmin
`TrackPointExtension` v2 schema so it is understood by common tools.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1"
     creator="CobbVision Companion/1.0"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>CobbVision 2026-09-26 14:05</name>
    <time>2026-09-26T21:05:00.000Z</time>
  </metadata>
  <trk>
    <name>CobbVision 2026-09-26 14:05</name>
    <trkseg>
      <trkpt lat="45.5231" lon="-122.6765">
        <ele>15.20</ele>
        <time>2026-09-26T21:05:01.000Z</time>
        <extensions>
          <gpxtpx:TrackPointExtension>
            <gpxtpx:speed>12.3400</gpxtpx:speed>
          </gpxtpx:TrackPointExtension>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
```

Fields:

| Element | Source | Format |
| --- | --- | --- |
| `metadata/name`, `trk/name` | session start (local time) | `CobbVision yyyy-MM-dd HH:mm` |
| `metadata/time` | `DriveSession.startedAtMs` | ISO 8601 UTC, millisecond precision |
| `trkpt@lat`, `trkpt@lon` | `TrackPoint.latitude/longitude` | degrees, full `Double` precision |
| `ele` | `TrackPoint.altitudeM` | metres, 2 decimals |
| `time` | `TrackPoint.timestampMs` | ISO 8601 UTC, millisecond precision |
| `gpxtpx:speed` | `TrackPoint.speedMS` | m/s, 4 decimals, clamped to ≥ 0 |

One `<trk>` with a single `<trkseg>` per session; an empty session yields a valid GPX
file with an empty `<trkseg>`. Horizontal accuracy is used for filtering but is not
written to the file. The export file is `cobbvision_<start epoch ms>.gpx` in the app's
cache directory.

The same format is produced by the iOS companion app, so the backend accepts either.
