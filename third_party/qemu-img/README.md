# qemu-img

The APK includes QEMU 11.1.1's standalone `qemu-img`, built from unmodified
[QEMU sources](https://download.qemu.org/qemu-11.1.1.tar.xz) for aarch64 Linux/musl.
It runs as a separate process under the Android app UID, without root or Termux.
The `.so` filename is for Android native executable packaging; this is a static
PIE executable, not a JNI library. ELF load segments are aligned to 16 KiB.
The binary SHA-256 is recorded in `binary.sha256`.

QEMU is GPL-2.0; see COPYING. Statically linked dependencies are glib 2.84.4,
libintl from gettext 0.24.1 (LGPL), PCRE2 10.46 (BSD), musl 1.2.5 (MIT),
zlib 1.3.2, zstd 1.5.7 (BSD), and GCC 14.2 libatomic (GCC Runtime Library
Exception). Their notices are in licenses/. No Termux binary or source patches
are used. QEMU and the respective library authors retain their copyrights.

## Build

On an ARM64 Docker host (or with ARM64 emulation enabled):

```sh
docker build --platform linux/arm64 -t terminal-plus-qemu-img -f tools/qemu-img/Dockerfile tools/qemu-img
```

The image's `/out/libqemu-img.so` is the executable. Copy it to
`app/src/main/jniLibs/arm64-v8a/`, update `binary.sha256`, and verify on Android.
Build flags and the base image digest are recorded in the Dockerfile. Alpine
package repositories can advance; linked package versions above describe the
checked-in executable. `sources.json` pins the QEMU/dependency source archives
and Alpine packaging recipes/patches used for this build. To rebuild a modified
library, use its Alpine recipe and relink/rebuild qemu-img with that library.

## Corresponding sources

```sh
python3 tools/qemu-img/source-bundle.py
```

This creates `dist/qemu-img-sources.tar.gz`, containing QEMU and linked library
sources, Alpine recipes/patches, notices and build instructions. Release CI
publishes it alongside the APK. Distribute this source archive with standalone
APK copies as well; it is deliberately not packed inside the APK. Do not publish
a new binary without updating and distributing its corresponding sources.
