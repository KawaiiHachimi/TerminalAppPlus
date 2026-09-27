# Termux terminal components

Vendored from https://github.com/termux/termux-app at commit
`8629e632fcb95da272221be327db653fb24befe9`.

Credit: Termux maintainers and contributors, and the Android Terminal Emulator
contributors whose code is incorporated upstream. See LICENSE.md and COPYING;
retain the copyright/license notices within the source files. The upstream
repository states GPLv3-only with Apache-2.0 exceptions for Android Terminal
Emulator-derived code. This directory is not covered solely by the root AOSP NOTICE.

Local changes: standalone Gradle build scripts (SDK 37), publishing removed, and
native PTY build disabled. TerminalSession is made non-final to allow the stream adapter; all other Java
source, resources, tests and JNI source are retained.
The debug-only AvfTerminalSession adapter lives in app/src/debug and overrides the
host-process/PTY path. No native Termux library or host shell is used by the probe.

Only the experimental debug variant depends on these modules. Any distribution
of a build incorporating them must retain applicable upstream licenses and supply
the corresponding source as required. This is not an official Termux build.
