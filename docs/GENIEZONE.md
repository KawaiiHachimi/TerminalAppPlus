# GenieZone guest-kernel compatibility

Test device: PKB110, Android 17 / API 37, `ro.hardware=mt6991`, `/dev/gzvm`.

Stock 5100000 image (downloaded 2026-09-27):
- build_id: ferrochrome/aarch64/hourly-11815-Sat Sep 05 04:01:36 UTC 2026
- Linux 6.12.92-android16-6-g4e585dd7f3b7-ab16266940-4k
- SHA-256: 1008c4aa740113c3bccd1aa72376bcd23602a2f94aa170d51617b7089c647343

Observed stock boot: hangs in `dma_atomic_pool_init`. `earlycon initcall_debug`
exposes the location; both match_host and one_cpu show the issue. The guest reports
KVM feature registers `0x743a004d 0x564bcaa9 0xe911c52e 0x00000ffd`, containing UID-like
values instead of a normal feature bitmap. The Android pKVM guest code registers
memory encryption/sharing callbacks on this result. Disabling that pKVM guest
initialization in this **non-protected** GenieZone guest allows Debian 13 to boot,
register its guest agent, serve ttyd, and access the network.

Source reference:
https://android.googlesource.com/kernel/common/+/android16-6.12/drivers/virt/coco/pkvm-guest/arm-pkvm-guest.c

Equivalent guest source change for this specific non-protected backend:

```c
void pkvm_init_hyp_services(void)
{
    return; /* non-protected GenieZone compatibility build only */
}
```

Terminal Plus locates `pkvm_init_hyp_services` by decompressing the kernel's
embedded kallsyms names. There is no SHA-256 whitelist or fixed patch offset.
The minimal reader supports little-endian, uncompressed ARM64 Image files with
the Linux 6.12 kallsyms layout (unsigned relative offsets after the token index).
ELF, compressed kernels, missing symbols and other kallsyms layouts are rejected.

Before patching, it checks the token index, symbol-count markers, kallsyms
self-references against file offsets, a unique target symbol, executable-section
bounds and the entry instruction. A PACIASP entry is replaced with RET; an
optional BTI C landing pad is preserved. An existing RET is accepted, making the
operation idempotent. These checks validate location and entry shape, not the
semantic compatibility of every future kernel implementation.

The original kernel is never overwritten. The patched bytes are written atomically
to `vmlinuz-terminal-plus`; an existing identical copy is reused. This is enabled
only when `/dev/gzvm` exists and the VM is non-protected. Parse or validation failure
leaves the original untouched and reports an error; no guessed offset is applied.

Local parser/patch validation covered the archived September 5100000 kernel and
4000000/latest kernels: offsets were resolved as `0xea8f7c`,
`0xe76b50`, and `0xe4f9d0`. This does not constitute boot verification of all three.
Run optional real-image tests with colon-separated absolute paths in
`PLUS_KERNEL_FIXTURES` and `:app:testDebugUnitTest --tests '*GuestKernelSymbolsTest'`.

On 2026-10-09, a fresh download from the 5100000 endpoint was also tested on
PKB110: the parser located the updated function at `0xeaa344`, the VM booted,
the AIDL guest agent registered, and the ttyd WebSocket connected successfully.

The verified boot device remains PKB110 / MT6991; other GenieZone devices have not
been individually validated. No host kernel, SELinux policy or protected-VM
security setting is changed. The ROM vendor should fix its hypervisor feature
reporting; this compatibility measure is not a general pKVM implementation.
