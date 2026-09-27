# Direct AVF console feasibility probe

Branch: `codex/feat-direct-console`. Debug terminal UI only; the normal LauncherActivity still starts the existing
Debian VM and ttyd. The toolbar console now attaches to that same VM. ConsoleProbeActivity is an engineering
probe, not a complete terminal emulator or an image importer.

## Confirmed on PKB110 / MT6991 / Android 17

A separate diskless VM using the known compatible guest kernel and a minimal
BusyBox initramfs accepted commands through AVF and returned:

```
DIRECT_CONSOLE_OK
Linux plus-console-probe 6.12.92-android16-6-g4e585dd7f3b7-ab16266940-4k ... aarch64 GNU/Linux
/dev/hvc0
PROBE_DONE
```

There is no ttyd, vsock bridge, guest agent, or Debian root disk in this test.
The required configuration is output capture plus `setVmConsoleInputSupported(true)`.
`setConnectVmConsole` is a separate facility and does not enable the input stream;
the probe leaves it false. Exactly one reader owns console output; the UI and file
receive copies of that stream. Log output is drained separately.

`hvc0` is a virtio console. `ttyS0` is the emulated serial port used in the U-Boot
probe. `tty0` is a Linux virtual screen console, not an interchangeable serial
stream. A distro must direct its console/getty to the matching device. Terminal
resize and ANSI rendering need a real terminal frontend for production use.

## Sources and artifacts

- [AOSP Android 15 custom VM guide](https://github.com/msft-mirror-aosp/platform.packages.modules.Virtualization/blob/android15-release/docs/custom_vm.md)
  documents crosvm U-Boot plus Debian nocloud ARM64 raw disks.
- [AOSP Android 17 guide](https://github.com/msft-mirror-aosp/platform.packages.modules.Virtualization/blob/android17-release/docs/custom_vm.md)
  describes the CLI route; the CLI/root instructions do not mean our app-owned
  console streams require root.
- Podroid's AVF ConsoleFanout was consulted to verify the API route; no code copied.
- U-Boot downloaded from AOSP `device/google/cuttlefish_prebuilts`,
  `bootloader/crosvm_aarch64/u-boot.bin` on main. Banner:
  `U-Boot 2024.04-gc8fc3d1d8ce6-ab13197479 (Mar 10 2025)`.
  SHA256: `9a9152956a1a21430d09f9ed79c70d054260a6e23ec28029b8cd44efe90d9e33`.
- Debian BusyBox: `busybox-static_1.37.0-6+b9_arm64.deb` from Debian's official pool.
  SHA256: `c833be48abfa16bc19c4966ec93e289ff1ce5d2f1476cad3a57bd105378cd15c`.
- Debian 13 nocloud ARM64 tar.xz from the official trixie/latest directory,
  dated 2026-09-14. Verified against its SHA512SUMS before use.

Downloaded binaries and guest disks stay outside Git. The existing `files/linux`
root disk is never attached by this probe. The diskless test only reads its kernel.
U-Boot mode does not use GuestKernelCompat and loads the distro kernel from disk.

## Reproduction

1. Build a static BusyBox initramfs:
   `python3 tools/build-console-initramfs.py /path/to/arm64/busybox /path/to/initrd.gz`.
2. Use the debug app's `run-as` to place this at `files/console-probe/initrd.gz`;
   place the crosvm U-Boot at `files/console-probe/u-boot.bin` and optionally a
   **separate disposable copy** of the Debian raw disk at `files/console-probe/debian.raw`.
3. Build and install debug APK. Grant its two AVF permissions as usual.
4. Force-stop Plus before launching a mode, so a previous Activity is not reused:

```sh
adb shell am force-stop com.android.virtualization.terminal.plus
adb shell am start -n com.android.virtualization.terminal.plus/com.android.virtualization.terminal.ConsoleProbeActivity
# U-Boot + disk: add --ez uboot true --ez disk true
# U-Boot without disk: add --ez uboot true
```

Logs: app-private `files/console-probe.txt`, `console-probe-vmm.txt`, and
`console-probe-error.txt` for setup failures. The debug UI now uses the Termux terminal view and emulator, connected directly to
AVF streams without PTY JNI. Tap the terminal to open the keyboard.
The diskless probe sends fixed diagnostic commands after a startup delay.
Official-disk mode never injects commands into unknown login/password prompts. Back/closing the Activity stops the test VM.
Do not use the disposable disk as the sole copy of important data.

## Official Debian disk result

U-Boot successfully discovered the independent virtio disk, loaded GRUB/EFI and
booted the image's own `6.12.107+deb13-arm64` kernel into Debian 13 systemd.
No Android guest-kernel binary patch was applied on this path. The image reached systemd-firstboot, including its interactive root-password
configuration prompts. A usable Debian login shell was **not** obtained in this probe.
The image's kernel command line had no explicit console= override; follow-up work
should select console/getty deliberately and handle the first-boot wizard.

Therefore: direct hvc0 input/output is proven, U-Boot and the official Debian
kernel/userspace boot are proven, but arbitrary-image login, graphics, networking,
resize and robust boot recovery are not yet validated. The test uses 1 CPU and
512 MiB only to keep the probe small; the regular VM settings remain unchanged.

The probe remembers its last explicit mode when the vendor task UI relaunches
the Activity without extras. Use `--ez uboot false` to explicitly return to the
diskless hvc0 probe. Each launch uses a separate VM registration. The Termux UI renders ANSI sequences and responds to terminal queries.
Guest-side resize synchronization is not implemented.

## Termux frontend integration

The debug probe now embeds the official Termux terminal-emulator/terminal-view
components (source revision and licenses in third_party/termux). The adapter
subclasses TerminalSession, replaces process/PTY creation and all writes with AVF
streams, and feeds received bytes into TerminalEmulator on the main thread.
AVF stream writes use a dedicated worker so keyboard/terminal-query responses
are not blocked by startup delays. Emulator resizing currently affects local
rendering only; it does not change guest tty dimensions.

Build, app lint and upstream terminal-emulator JVM tests passed. On-device
Termux rendering displayed the BusyBox prompt and direct-console diagnostic
results without raw escape sequences. Official-disk firstboot/login remains
a separate incomplete validation item.

The experimental page now follows Termux's basic interaction model: black terminal,
two extra-key rows, sticky Ctrl/Alt, Escape/Tab/navigation keys, keyboard toggle,
pinch font scaling, long-press selection and a left-edge drawer for probe modes.
A debug-only console button to the left of Settings on the normal terminal
toolbar opens the experiment; it does not add a second launcher icon. Insets keep
the terminal and key rows above the IME. Hardware-key input through TerminalView
was verified with `echo 1` returning `1`; the IME and key rows were visually checked
on the device. Ctrl/Alt are sticky until tapped again. This is still a single-VM
probe, not a full Termux multi-session clone. All 145 upstream emulator JVM tests
passed using upstream's Android stub configuration.

## Font and entry placement

The console action is immediately left of Settings in the normal terminal toolbar.
No separate launcher icon is added. The terminal loads the device font file
/system/fonts/DroidSansMono.ttf directly rather than the themed monospace alias.
On PKB110 it is app-readable and matches the AOSP font SHA256
`db19a1fdaba41cc4a2fec0330e5c15e71c6dd68a3ef074f4f28268828b45c862`.
No TTF is bundled. Devices lacking the file fall back to Typeface.MONOSPACE.

## Shared VM console

The toolbar action defaults to the running VmController VM. ConsoleProbeActivity
only creates a separate VM when launched with an explicit lab/uboot mode. Logger
is the single console reader and VmConsole distributes ordered byte copies to
subscribers, retaining bounded history. Replay disables terminal-query writes so
old DSR sequences are not answered into the current guest prompt. Closing the
shared console unsubscribes without stopping the VM.

The console does not configure users, passwords or getty. Login and authentication
are entirely controlled by the guest image. No ttyd credentials are injected.
The temporary autologin experiment was removed from both source and the test guest.
The future initial image selector/laboratory UI is not implemented; the U-Boot
probe and independent disk remain available.

Shared-VM validation: ttyd and direct console both returned boot ID
`7968d58b-6036-4e01-844c-31e2881ee67b`. The temporary autologin drop-in was then
removed and serial-getty restarted with the distro defaults; original plus1
cidata was restored on the device. No username/password policy remains in the
implementation. Build and lint passed after the rollback.

## Entry/appearance update

The console drawer and its duplicate navigation/clipboard buttons were removed.
Settings → Laboratory, above Recovery, now contains the BusyBox/hvc0, U-Boot +
Debian, and current-VM screen prototype entries (debug builds). The regular toolbar
console still attaches to the current VM. Pinch font size persists in
terminal_console_appearance/font_size_sp and restores before the terminal is shown.
The chosen font file and the existing terminal emulator remain unchanged.

## Managed imports supersede the laboratory probes

The laboratory now contains only the custom U-Boot image importer. Imported VMs use the main VmController and shared console; closing the console does not stop them. Legacy explicit debug intents remain available for development. See [custom VM usage](../CUSTOM-VM.md).
