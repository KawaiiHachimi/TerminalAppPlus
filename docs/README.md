# 文档索引

## 当前使用指南

| 文档 | 内容 |
| --- | --- |
| [项目 README](../README.md) | 构建、授权和功能入口 |
| [自动构建与发布](RELEASING.md) | Actions、签名、标签和 Release 产物 |
| [虚拟机管理](CUSTOM-VM.md) | 镜像导入、U-Boot/内核启动、切换、资源配置 |
| [自定义镜像 Guest 工具盘（实验）](GUEST-TOOLS-ISO.md) | 挂载只读 ISO，一次安装 ttyd 与图形采集服务 |
| [自定义镜像接入 ttyd](CUSTOM-TTYD.md) | 认证方式、临时启动、完整 systemd 配置和排查 |
| [Guest 图形采集与端口代理](GUEST-SCREEN-SERVICE.md) | 安装包部署、完整 systemd 配置、图形限制和排查 |

两篇 Guest 服务指南都按“连接原理 → 准备条件 → 安装/启动 → systemd → 维护 → 排查 → 来源”组织。命令均在 Guest 内执行，不是 Android ADB shell 命令。

## 来源与维护

- [上游来源及移植边界](UPSTREAM.md)
- [原版基线及提交组织](HISTORY.md)
- [源码核对与精简建议](SOURCE-AUDIT.md)：2026-09-28 的工作区审阅快照，后续改动不自动计入该快照。
- [Termux 来源和许可](../third_party/termux/README.md)
- [U-Boot 来源和许可](../third_party/u-boot/README.md)

## 兼容性与历史验证

以下记录保留特定日期、设备和开发阶段的证据；旧命令及功能描述不应作为当前安装指南。

- [MT6991 / GenieZone 内核兼容](GENIEZONE.md)：当前仍使用的精确哈希兼容措施及限制。
- [镜像版本对比](IMAGE-VERSIONS.md)：2026-09-27 取得的包内元数据，远程目录内容可能更新。
- [系统内置终端限制](BUILTIN-TERMINAL.md)：指定 ROM 的权限和 SELinux 观察。
- [分阶段实机验证](VALIDATION.md)
- [串口控制台实验](experiments/DIRECT-CONSOLE.md)
- [KMS 图形采集实验](experiments/KMS-CAPTURE.md)

已完成且与当前说明重复的 `plans/VM-MANAGEMENT.md` 已移除；当前使用方法集中在虚拟机管理指南，源码精简建议集中在源码核对文档。旧计划仍可从 Git 历史查看。
