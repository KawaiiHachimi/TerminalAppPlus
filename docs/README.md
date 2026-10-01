# 文档索引

## 当前使用指南

| 文档 | 内容 |
| --- | --- |
| [应用文件访问](APP-FILES.md) | 在 MT 等文件管理器中通过 SAF 管理应用数据 |
| [项目 README](../README.md) | 构建、授权和功能入口 |
| [自动构建与发布](RELEASING.md) | Actions、签名、标签和 Release 产物 |
| [虚拟机管理](CUSTOM-VM.md) | 镜像导入、U-Boot/内核启动、切换、资源配置 |
| [自定义镜像 Guest 工具盘（实验）](GUEST-TOOLS-ISO.md) | 挂载只读 ISO，一次安装 ttyd 与图形采集服务 |
| [初始配置 cloud-init（实验）](CLOUD-INIT.md) | 为兼容云镜像设置首次启动用户、密码和 SSH 公钥 |
| [AlmaLinux 网络兼容排查](ALMALINUX-NETWORK.md) | 接口匹配、DHCP/DNS 校验和异常及手动 workaround |
| [自定义镜像接入 ttyd](CUSTOM-TTYD.md) | 认证方式、临时启动、完整 systemd 配置和排查 |
| [Guest 图形采集与端口代理](GUEST-SCREEN-SERVICE.md) | 安装包部署、完整 systemd 配置、图形限制和排查 |

常规安装优先使用 Guest 工具盘指南；ttyd 和图形服务文档用于手动部署与排查。服务命令均在 Guest 内执行，不是 Android ADB shell 命令。

## 来源与维护

- [上游来源及移植边界](UPSTREAM.md)
- [原版基线及提交组织](HISTORY.md)
- [源码核对与精简建议](SOURCE-AUDIT.md)：2026-09-28 的工作区审阅快照，后续改动不自动计入该快照。
- [Termux 来源和许可](../third_party/termux/README.md)
- [U-Boot 来源和许可](../third_party/u-boot/README.md)

## 兼容性与历史验证

以下记录保留特定日期、设备和开发阶段的证据；旧命令及功能描述不应作为当前安装指南。

- [GenieZone 内核兼容](GENIEZONE.md)：当前仍使用的精确哈希兼容措施及限制。
- [镜像版本对比](IMAGE-VERSIONS.md)：2026-09-27 取得的包内元数据，远程目录内容可能更新。
- [系统内置终端限制](BUILTIN-TERMINAL.md)：指定 ROM 的权限和 SELinux 观察。
- [分阶段实机验证](VALIDATION.md)
- [串口控制台实验](experiments/DIRECT-CONSOLE.md)
- [KMS 图形采集实验](experiments/KMS-CAPTURE.md)

已完成且与当前说明重复的 `plans/VM-MANAGEMENT.md` 已移除；当前使用方法集中在虚拟机管理指南，源码精简建议集中在源码核对文档。旧计划仍可从 Git 历史查看。

## 代码与构建资源位置

- `app/`：Android 应用、界面、VM 生命周期及运行时资源。
- `guest/root_files/`：Guest 常驻服务源码；`tools/`：安装器、资源生成与验证脚本。
- `third_party/`：固定的第三方组件、来源和许可。ttyd 与 qemu-img 是不同运行环境的程序，前者运行在 Linux Guest，后者运行在 Android。
- `app/src/main/assets/` 中的 ISO/bundle 是生成产物，需通过 README 对应命令更新，不能只改打包后的副本。
- `docs/experiments/`、验证记录及源码核对是历史材料，当前操作以本页“当前使用指南”为准。
