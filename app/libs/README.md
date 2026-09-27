# Compile-only Android platform signatures

`android-hidden-37.jar` is **compileOnly**. It is not dexed or included in the APK.
Android 17's installed framework/AVF supplies the implementation at runtime.

Source: https://github.com/Reginer/aosp-android-jar/blob/262f6ae931160011572a5adfe4ec6302585e8f60/android-37/android-jdk21.jar

SHA-256: `694b291a046b4ba1738e9df61577ec8cfddf250a7bbe9867a72ff19e161ea692`

Download URL:
https://raw.githubusercontent.com/Reginer/aosp-android-jar/262f6ae931160011572a5adfe4ec6302585e8f60/android-37/android-jdk21.jar

The regular Android SDK does not expose the custom-image AVF API used by AOSP
TerminalApp. Keeping this dependency local allows Android Studio to sync without
an AOSP checkout or platform signing key. Public SDK APIs take precedence; uses
of hidden methods on public classes were replaced with public equivalents.
