# Ferrochrome 镜像目录实测对比（2026-09-27）

目录数字是 Terminal/Ferrochrome 镜像系列标识，不是 Android API 等级，也不是 Debian 版本。
同一目录的文件可能重新发布，`latest` 也不能假定比所有数字目录新。
以下值来自实际下载包内 build_id、vm_config.json 及 vmlinuz 的 Linux version 字符串。

| 目录 | 包内构建日期 | 内核 | 启动/配置差异 |
|---|---|---|---|
| 3500000 | 2024-12-10 | 未从此包提取独立内核；不能据目录名推断版本 | ROOT + EFI 分区；配置无独立 kernel/initrd/cidata；旧启动布局 |
| latest | 2026-01-22 (hourly-6422) | 6.12.60-android16-6，4K | kernel + initrd + cidata；2d GPU；自动内存气球；60 秒启动超时 |
| 4000000 | 2026-01-28 (hourly-6572) | 6.12.63-android16-6，4K | 与本次 latest 的 vm_config 结构相同；内核更新 |
| 5100000 | 2026-09-05 (hourly-11815) | 6.12.92-android16-6，4K | 当前本地 AOSP Terminal 默认系列；本机实际启动为 Debian 13 |

3500000 的 build_id 是 `eng-kokoro-gcp-ubuntu-qa-1213545027-Tue Dec 10 07:20:29 UTC 2024`。
4000000 和 latest 的包都配置 match_host CPU 拓扑、4096 MiB 内存、非受保护 VM、网络、
/storage/emulated 和应用 files 目录共享。App 可以覆盖配置中的内存值，因此包写 4 GB
不代表界面默认一定是 4 GB。

旧镜像不宜只改 URL 混用：Terminal 的 cidata、guest agent（旧 gRPC / 新 AIDL）、ttyd
连接协议、内核模块及文件布局需要匹配。本地 Android 17 Terminal 的兼容检查还要求
镜像构建年份至少为 2026，3500000 会触发升级。

链接：
- https://dl.google.com/android/ferrochrome/3500000/aarch64/images.tar.gz
- https://dl.google.com/android/ferrochrome/4000000/aarch64/images.tar.gz
- https://dl.google.com/android/ferrochrome/latest/aarch64/images.tar.gz
- https://dl.google.com/android/ferrochrome/5100000/aarch64/images.tar.gz
