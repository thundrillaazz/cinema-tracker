# Cinema Tracker 5.0.0 — Release First

## What's changed
- Rebuilt the single-file Vanilla JS UI in `app/src/main/assets/index.html`.
- Removed watched/history and watch-status note concepts entirely.
- Watchlist is release-date-first with Indonesia release date and days remaining.
- Save state updates immediately on Home, Search, detail sheet and Watchlist.
- Added SharedPreferences persistence for complete UI state alongside localStorage.
- Theme selection updates Android status/navigation bars.
- Kept the existing Kotlin WebView host, MediaStore poster saving, launcher aliases, WorkManager radar and GitHub Actions workflow.
- Uses lazy poster loading, bounded home cache, debounced search, and only fetches detailed metadata when a user opens a movie.

## Setup
1. Upload/extract the project contents to your GitHub Codespace repository root.
2. Push to `main` or `master`, or run the workflow manually from GitHub Actions.
3. Install the generated `CinemaTracker-v5.0.0-debug` APK artifact.
4. Open Settings in the app and enter a TMDB API key (v3 API key).

## Build locally
Requires JDK 17, Android SDK and Gradle 8.7:

```bash
gradle assembleDebug --no-daemon
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Notes
- This archive did not contain the original frontend source; the UI was rebuilt from the supplied screen recording and integrated with the available Android source.
- TMDB API key is stored on-device. Do not commit a personal API key to source control.
- WorkManager periodic work is Android-scheduled and may run later than exactly six hours due to OS battery optimizations.
- The app does not promise zero battery/data use: catalog and poster data require network access; background radar only runs when network is available.
