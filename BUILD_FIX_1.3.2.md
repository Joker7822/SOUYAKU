# SOUYAKU 1.3.2 Android build fix

## Problem

OkHttp 5.5.0 resolves to `okhttp-android:5.5.0`, whose AAR metadata requires compileSdk 37. The SOUYAKU Android project is intentionally on compileSdk / targetSdk 36 with Android Gradle Plugin 8.13.2.

## Fix

The packaged Android project pins:

```kotlin
implementation("com.squareup.okhttp3:okhttp:5.3.2")
```

and keeps:

- compileSdk 36
- targetSdk 36
- AGP 8.13.2
- JDK 17

Version is bumped to 1.3.2 / versionCode 7.

A ready-to-build source package is stored at `releases/SOUYAKU_Interpreter_1.3.2_Render_WSS_buildfix.zip`.
