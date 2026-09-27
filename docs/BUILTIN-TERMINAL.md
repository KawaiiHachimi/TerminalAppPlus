# Built-in Terminal findings

PKB110 / Android 17, 2026-09-27:

- Built-in package is in `/apex/com.android.virt/priv-app/VmTerminalApp@CP2A.260605.016`.
- SYSTEM + PRIVILEGED, not debuggable; `run-as` explicitly rejects it.
- `ro.debuggable=0`; AOSP's /sdcard/linux custom-image path is guarded by
  Build.isDebuggable and is not available on this production ROM.
- Native display is reached via ServiceManager's
  `android.system.virtualizationservice` -> waitDisplayService ->
  ICrosvmAndroidDisplayService.setSurface. Plus's untrusted_app domain is denied
  service lookup. The two development grants pass AVF permission checks but do
  not alter the SELinux domain.
- Plus boots the same downloaded 5100000 guest after disabling a wrongly enabled
  pKVM guest initialization on this non-protected GenieZone VM. Its ttyd setup is
  still the AOSP cidata ttyd_uds + ttyd_vsock_bridge units.
- Built-in Terminal's earlier logs also showed crosvm creation followed by an
  unready terminal, but its current private kernel cannot be read with ordinary
  adb. The identical guest-kernel cause is strongly suggested, not independently
  verified by directly patching its private image.

Ordinary adb cannot write the system application's private VM image, and an APK
signed with a local debug key cannot update the system-signed Terminal package.
Do not disable SELinux or represent pm grant as granting access to system services.
