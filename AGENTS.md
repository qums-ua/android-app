# Quarp

WebView wrapper Android app for [QUMS](https://qums.quantumuniversity.edu.in) (Quantum University Management System).

## Project Structure

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 37
- **Build System**: Gradle with Kotlin DSL and version catalog (`gradle/libs.versions.toml`)
- **Package**: `me.guptaishaan.quarp`

## Architecture

Single-activity app. `MainActivity` hosts a Compose `Scaffold` containing an `AndroidView`-wrapped `WebView` that loads the QUMS portal. No multi-module setup — everything lives in the `:app` module.

### Key Files

- `app/src/main/java/me/guptaishaan/quarp/MainActivity.kt` — Activity + `QumsWebView` composable
- `app/src/main/AndroidManifest.xml` — Declares INTERNET permission and the launcher activity
- `app/build.gradle.kts` — App-level dependencies (Compose, WebKit)
- `gradle/libs.versions.toml` — Centralized version catalog

## WebView Configuration

- JavaScript and DOM storage enabled (required by QUMS)
- All navigation stays inside the WebView (no external browser launch)
- Back button navigates web history before exiting the app
- No loading indicator — pages load silently

## Building

```bash
./gradlew assembleDebug
```

## Dependencies

Managed via `gradle/libs.versions.toml`. Key additions beyond the default Compose template:

- `androidx.webkit` — Modern WebView utilities
