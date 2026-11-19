# Reelwell (Android)

Native Android (Kotlin + Jetpack Compose) port of the Reelwell web app. Paste or share an
Instagram, Facebook or TikTok link and download the public video, audio or photos.
Link resolving runs on-device (no backend needed). Files are saved to `Downloads/Reelwell`.

- Package: `com.reelwell.app`, minSdk 26, targetSdk 34
- Share any link to Reelwell from the Instagram / Facebook / TikTok share sheet

## Build the debug APK

**GitHub Actions:** push to `main`/`master` (or run *Build Debug APK* manually).
Download the `Reelwell-debug-apk` artifact from the workflow run.

**Locally:** open in Android Studio, or run `gradle assembleDebug` (Gradle 8.7, JDK 17).
Output: `app/build/outputs/apk/debug/app-debug.apk`.
