# Validation — 2026-09-27

Device: PKB110, OP5A3DL1, MT6991, Android 17 API 37, arm64-v8a.
AVF advertises `android.software.virtualization_framework`; crosvm selects GenieZone.
The application runs with `u:r:untrusted_app`, not the original terminal's vmlauncher_app domain.

Observed guest output after restoring match_host / 4096 MiB:

```
nproc
8
free -m
               total        used        free      shared  buff/cache   available
Mem:            3907         420        3454           4         244        3487
Swap:            976           0         976
uname -r
6.12.92-android16-6-g4e585dd7f3b7-ab16266940-4k
systemctl is-active terminal-plus-proxy
active
```

Port forwarding: spawned a temporary Python HTTP server on guest 127.0.0.1:18080,
used the original port-control UI to enable forwarding, then accessed phone
127.0.0.1:18080 through an adb localhost forward. HTTP returned exactly
`terminal-plus-port-forward-ok`. Disabled forwarding and stopped the test service.
No externally reachable host listener was opened.

Stock-image failure was reproduced with both host CPU topology and one CPU.
Early console + initcall_debug identified dma_atomic_pool_init. A hash-checked
non-protected guest kernel fix enabled boot; 8 CPUs / 4 GB were subsequently
validated. Temporary one_cpu, cma=0 and initcall_debug settings were removed.

Raw transient build logs, guest outputs and earlier diagnostics are under artifacts/
(ignored by Git). No generic emulator boot can substitute for this AVF hardware test.
Native VM display is blocked by the target ROM's ServiceManager SELinux policy;
the unavailable native display entry is now hidden and is not counted as a passing feature.

## Native display entry removal, GPU retained — 2026-09-27

Installed the corrected APK and restarted the existing guest on PKB110.
The terminal toolbar has no native display button; the legacy display Activity is
unregistered, and the native-surface resolution setting is hidden.
GPU/rendering configuration, virtual display/input devices, renderer preferences
and bundled guest resources match the preceding working baseline.

Guest command input/output passed: nproc = 8, memory = 3907 MiB,
terminal-plus-proxy = active, and /dev/dri contains card0 and renderD128.
These device nodes confirm that the virtual GPU is present, not that hardware
3D acceleration has been validated. APK compilation and lint passed.

The temporary headless guest profile and plus2 cidata from the discarded changes
were restored on the test device to the original Plus profile and plus1 cidata,
without resetting the guest root disk.

## Shizuku onboarding — 2026-09-27

Built APK and lint successfully. Installed on PKB110, revoked both Plus AVF
development grants without clearing data, and opened the initial dialog.
Confirmed the Shizuku configuration button, Copy and Check again controls.
After using the Shizuku flow, dumpsys reported both AVF grants and Shizuku access
as granted; the app entered the terminal and the guest agent registered.
No adb pm grant was used to restore these permissions during this test.
Shizuku-unavailable and denial messages are implemented but those branches were
not exercised in this hardware run.
