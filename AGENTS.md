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

- `app/src/main/java/me/guptaishaan/quarp/MainActivity.kt` — Activity + `QumsWebView` composable + download handling
- `app/src/main/AndroidManifest.xml` — Declares INTERNET, WRITE_EXTERNAL_STORAGE permissions, launcher activity, and FileProvider
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths for sharing downloaded file URIs
- `app/build.gradle.kts` — App-level dependencies (Compose, WebKit)
- `gradle/libs.versions.toml` — Centralized version catalog
- `app/src/main/res/values-night/themes.xml` — Dark mode Material3 theme variant

## WebView Configuration

- JavaScript and DOM storage enabled (required by QUMS)
- All navigation stays inside the WebView (no external browser launch)
- Back button navigates web history before exiting the app
- No loading indicator — pages load silently
- File uploads (`<input type="file">`) use the system file picker via `onShowFileChooser` + `ActivityResultLauncher`
- File downloads are intercepted by a `DownloadListener` that shows a `MaterialAlertDialogBuilder` confirmation dialog before downloading
- After download completes, a dialog offers to open the file with the system-recommended app
- Downloads use `DownloadManager` and save to the public Downloads folder
- A `FileProvider` is configured to share file URIs on pre-Android 10 devices
- After every page load, JS sets `autocapitalize="characters"` on the `#captcha` input so mobile keyboards default to uppercase

### WebChromeClient Overrides

The `WebChromeClient` in `QumsWebView` only overrides what's necessary:

| Override | Purpose |
|---|---|
| `onShowFileChooser` | Delegates `<input type="file">` to the system file picker via an `ActivityResultLauncher` owned by `MainActivity` |
| `onPermissionRequest` | Auto-grants geolocation/media permissions (trusted QUMS portal) |

JS dialogs (`alert`, `confirm`, `prompt`) and `<select>` dropdowns use the WebView's default handling, which inherits the Activity's Material3 theme and renders natively.

**Pattern**: The `WebChromeClient` lives inside the `QumsWebView` composable's `factory` lambda. For interactions that need an `ActivityResultLauncher` (file upload), a callback is passed from `MainActivity` which owns the launcher. This keeps the composable decoupled from Activity lifecycle.

### Dark Mode

- The app follows the **system dark/light mode** setting automatically
- XML theme has a `values-night/themes.xml` variant (`Theme.Material3.Dark.NoActionBar`) so dialogs, status bar, and system chrome adapt
- The Compose `QuarpTheme` already uses `isSystemInDarkTheme()` with dynamic colors (Android 12+) and fallback dark/light color schemes
- WebView content stays in its original colors — algorithmic darkening is disabled because it breaks the QUMS site layout

## Building

```bash
./gradlew assembleDebug
```

## Dependencies

Managed via `gradle/libs.versions.toml`. Key additions beyond the default Compose template:

- `androidx.webkit` — Modern WebView utilities
- `com.google.android.material:material` — Material3 XML themes and `MaterialAlertDialogBuilder`
