# Building the Android port

## Requirements

- Java 17+
- Android SDK with platform 35 (`sdk.dir` in `local.properties` or `ANDROID_HOME` env var)
- Your decrypted copy of *The Heist* v1.1.2 (`.ipa`)
- A connected device or emulator for installation

## Build

```
./gradlew :android:assembleDebug -PheistIpa="/path/to/iPhone - The Heist.ipa"
```

Output: `android/build/outputs/apk/debug/android-debug.apk`

The IPA path can also be set persistently:

- `heistIpa=/path/to/...` in `local.properties`
- `HEIST_IPA` environment variable

## Install

```
adb install -r android/build/outputs/apk/debug/android-debug.apk
```

## Supported Android versions

| Version | API | Notes |
|---------|-----|-------|
| Android 2.3.x (Gingerbread) | 9–10 | Minimum supported. Runs but expect slower hub navigation and no level completion thumbnails on very low RAM devices. |
| Android 3.x (Honeycomb) | 11–13 | Additive blend mode available from API 11; fully functional. |
| Android 4.x – 6.x (ICS through Marshmallow) | 14–23 | Fully functional. Camera permission requested at runtime from API 23. |
| Android 7.x+ (Nougat and above) | 24+ | Fully functional, smooth performance. |

The front-camera self-view during the prize video call requires Android 5.0 (API 21) or higher. On older versions the call plays without a self-view — no error.

## Notes

The build unpacks and converts the IPA's assets and packages them directly into the APK.

The app uses the original bundle ID (`com.taptaptap.TheHeist`), name and icon.

In debug builds a 3-finger tap opens the developer menu.

### Known quirks on older devices

- **Hub scene performance**: the hub renders a full room scene with animated elements every frame in software. On single-core devices below ~1 GHz this can feel sluggish between puzzles; the puzzles themselves run smoothly.
- **Level completion thumbnails**: generating the thumbnail after finishing a level requires a large offscreen bitmap. On devices with very little heap space the thumbnail is silently skipped — the icon stays as the default art.
- **Prize video call delay**: the end-of-game video is decoded in software. On slow hardware there may be a delay of several seconds before playback begins.
