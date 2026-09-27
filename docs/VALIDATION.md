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
that limitation is surfaced in the UI and is not counted as a passing feature.
