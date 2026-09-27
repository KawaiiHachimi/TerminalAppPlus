# MT6991 / GenieZone guest-kernel compatibility

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
    return; /* non-protected MT6991 GenieZone compatibility build only */
}
```

To avoid requiring a Linux kernel build environment, Terminal Plus derives a
separate guest kernel file from the **exact known hash**, replacing the first
instruction of `pkvm_init_hyp_services` (offset 0xea8f7c, `3f2303d5` PACIASP) with
`c0035fd6` RET. Offset was verified against the image's recovered kallsyms and
AArch64 disassembly. Result SHA-256:
`e8250ecd77b159e9013e3816ce8dece12e58fc0758d3fbb24277c640e67cf717`.

The original vmlinuz is never overwritten. This is restricted to MT6991 with
/dev/gzvm and a non-protected config. Unknown hashes fail with a diagnostic rather
than patching an unverified offset. No host kernel, SELinux policy, system file or
protected-VM security setting is changed. Other devices use the unmodified image.
The ROM vendor should fix its hypervisor feature reporting; this narrowly scoped
compatibility measure is not a general kernel patch or a claim of pKVM support.
