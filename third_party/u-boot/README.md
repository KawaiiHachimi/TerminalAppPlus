# Bundled crosvm ARM64 U-Boot

Taken from the development device at `/apex/com.android.virt/etc/u-boot.bin`.
Version: `U-Boot 2024.04-g3fe964757589-ab15108624` (2026-03-27).
SHA256: `f464a92c6086fa876c0bc775397d20b7491b6b34e2260feb0e19b5ca97f2dd30`.

U-Boot is a separate program run inside the VM, licensed under GPL-2.0; see [COPYING](COPYING).
The source revision matching its version banner is [AOSP external/u-boot 3fe9647575890b846172e546201eff7614c8cb59](https://android.googlesource.com/platform/external/u-boot/+/3fe9647575890b846172e546201eff7614c8cb59/).
[Corresponding source archive](https://android.googlesource.com/platform/external/u-boot/+archive/3fe9647575890b846172e546201eff7614c8cb59.tar.gz).

The app prefers a readable system APEX U-Boot, copies it into the VM's private directory, and uses this bundled binary when the system copy is inaccessible. Existing VMs keep their chosen bootloader.
