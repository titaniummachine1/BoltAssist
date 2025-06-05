# BoltAssist

BoltAssist is a simple Android application built with Jetpack Compose. The default screen displays a greeting using the project's custom Material theme. The project is intended as a minimal template for experimenting with Compose.

## Prerequisites

- **Android Studio**: version 2024.1 (Koala) or newer. The project uses the Android Gradle Plugin `8.2.1` and Kotlin `1.9.22`.
- **Android SDK**: API level 34 installed. The module targets SDK 34 and requires a minimum SDK level of 30.
- Ensure `sdk.dir` is defined in a `local.properties` file or set the `ANDROID_HOME` environment variable so Gradle can locate your Android SDK.

## Building from the command line

The repository ships with the Gradle wrapper. From the project root you can assemble the APKs without opening Android Studio:

```bash
# Compile the debug variant
./gradlew assembleDebug

# Compile the release variant
./gradlew assembleRelease
```

To install directly on a connected emulator or device you can run:

```bash
./gradlew installDebug
```

The resulting APKs are written to `app/build/outputs/apk/`.

## Running in Android Studio

Open the project in Android Studio and click **Run** on the `app` module. Android Studio will build and deploy the application to your selected emulator or device.

