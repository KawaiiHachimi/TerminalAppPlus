#!/bin/sh
set -eu
ADB="${ADB:-adb}"
PKG=com.android.virtualization.terminal.plus
"$ADB" shell pm grant "$PKG" android.permission.MANAGE_VIRTUAL_MACHINE
"$ADB" shell pm grant "$PKG" android.permission.USE_CUSTOM_VIRTUAL_MACHINE
"$ADB" shell am start -n "$PKG/com.android.virtualization.terminal.LauncherActivity"
