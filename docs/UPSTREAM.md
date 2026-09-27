# Sources and porting decisions

- Base: local AOSP packages/modules/Virtualization, commit
  `22f1c9ee92b146e10c8f5e71338618f77ab1631e` (26Q2 / Android 17).
  `android/TerminalApp` Kotlin, resources, JavaScript and arm64 cidata are retained.
- AOSP `libs/debian_service`: protobuf and Binder interfaces.
- AOSP `libs/android_display_backend`: display Binder interface.
- AOSP `android/virtualizationservice`: Binder dependency closure for the internal
  display service. Transaction ordering is generated from original AIDL, not guessed.
- AOSP `DeviceProperties.java`: standalone helper, no test framework dependency.
- Podroid reference: https://github.com/ExTV/Podroid at
  `ce0121896b2895637ab1f656cbb0d75fa2874489`.
  Applied the development-permission + process-local HiddenApiBypass approach.
  No Podroid application code, guest image or GPL library was copied.
- Independent Gradle/frontend reference: https://github.com/robertkirkman/TerminalApp
  at `3aaf0be4d5233622d1bcd736f59e2d6279a162f2`.
  Its fork removes AVF and connects to Termux ttyd, so its backend was not adopted.
  Gradle wrapper launchers were reused and the wrapper version was updated.

Terminal Plus retains the AOSP source namespace but uses an independent
applicationId, FileProvider authority and display task affinity. It uses ordinary
APK signing, no shared system UID, no privileged installation, and no root command.

Standalone feature flags select the new AOSP Compose UI and vsock terminal
transport. System feature-flag settings are not modified. Gradle generates the
protobuf/gRPC Java code and SDK AIDL (`--rpc` for guest Binder RPC); source generation
does not require Soong. `ForwarderHost` uses AVF-owned vsock connections to a small bundled guest
localhost proxy. This replaces the Soong-specific Rust JNI library and the reverse-vsock
listener that untrusted_app SELinux forbids. The original port-control UI is retained.

The hidden-API signature JAR is only a compile dependency; see `app/libs/README.md`.
