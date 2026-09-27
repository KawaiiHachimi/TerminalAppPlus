# Repository history

This repository starts with a byte-for-byte snapshot of the AOSP
`android/TerminalApp` subtree at Virtualization commit
`22f1c9ee92b146e10c8f5e71338618f77ab1631e`.
The `aosp-17-baseline` tag preserves the original source layout, Soong files,
README and both architecture-specific cidata assets.

The following commits organize the previously developed local changes into
reviewable implementation steps. They are a reconstruction of the port, not
original upstream history or a claim that every intermediate commit was tested
as a standalone working application. Validation applies to the completed port.

1. Import pristine Android 17 AOSP TerminalApp.
2. Move sources into a standalone Gradle/Android Studio project and add build dependencies.
3. Introduce the Plus identity, development-permission onboarding and ordinary-app compatibility.
4. Connect ttyd through AVF and fix VM startup/logging lifecycle handling.
5. Apply the narrowly scoped GenieZone guest kernel compatibility fix.
6. Add guest port forwarding, cidata resources and proxy tests.
7. Document build instructions, image differences and device validation.

All commits use `KawaiiHachimi <bilibili@att.net>` as both author and committer.
Upstream copyright and license notices remain intact.

To inspect the original sources without changing a running development checkout:

```sh
git archive aosp-17-baseline -o aosp-terminalapp-original.tar
```

Build caches, local SDK configuration, device logs, downloaded VM images and
locally signed APK outputs are excluded from Git. The compile-only signature JAR
and bundled cidata are included so a clone contains the required source inputs.
