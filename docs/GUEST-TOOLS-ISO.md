# 自定义镜像 Guest 工具盘（实验）

适用于 Debian/Ubuntu、systemd，以及支持 virtio-vsock 的 Guest 内核。App 在启动自定义 img/raw 虚拟机时，将只读 `PLUS_TOOLS` ISO 追加到已有磁盘之后，支持 U-Boot 和直接内核启动；不改变原系统磁盘顺序，不修改保存的 vm_config.json。官方格式镜像包继续使用原来的 cidata 路线。

## 首次安装

更新 APK 后，先在 Guest 内正常关机，再重新启动虚拟机，使新增磁盘生效。在 App 的“设置 → 虚拟机 → 配置 → Guest 工具”可以复制安装命令。

在 Guest 控制台登录后（支持 root 和普通用户）执行：

```sh
(plus_sudo=; [ "$(id -u)" = 0 ] || plus_sudo=sudo
  $plus_sudo mkdir -p /mnt/terminal-plus &&
  (mountpoint -q /mnt/terminal-plus || $plus_sudo mount -o ro /dev/disk/by-label/PLUS_TOOLS /mnt/terminal-plus) &&
  $plus_sudo sh /mnt/terminal-plus/install.sh --user "$(id -un)")
```

ttyd 使用当前登录用户运行 shell；root 无需 sudo。也可以将 `"$(id -un)"` 替换为其他已有用户名。脚本不会创建账户或修改密码。

脚本从工具盘读取本地服务代码，按需通过 apt 安装 python3、liblz4-1、ttyd 和 socat。缺少软件包时需要联网。本次新安装 ttyd 软件包自动启动的默认 ttyd.service 会被停用，避免与 Plus 的 7681 端口冲突；安装前已运行的 ttyd 仍会触发保护检查。它配置 ttyd 的指定用户 shell、vsock 7681 桥接，以及统一图形采集/端口代理服务，并启用开机启动。再次执行可覆盖安装 Plus 自己的服务配置；请从控制台执行，因为重启 ttyd 会断开网页终端。

已有其他 ttyd 服务时，使用以下命令只安装图形采集和端口代理：

```sh
sh /mnt/terminal-plus/install.sh --skip-ttyd
# 普通用户在命令前加 sudo
```

安装成功后回到 App 打开 ttyd 或虚拟显示器。文件已经复制到 Guest 系统盘，后续启动无需重复安装；工具盘仍会附加，供手动更新使用。App 不会自动执行该脚本。

## 状态与排查

```sh
systemctl status terminal-plus-ttyd terminal-plus-ttyd-vsock terminal-plus-guest --no-pager
journalctl -u terminal-plus-guest -n 50 --no-pager
lsblk -o NAME,SIZE,FSTYPE,LABEL
```

- 找不到 `PLUS_TOOLS`：确认运行的是本实验分支 APK、自定义 img/raw VM，并已重新启动。没有 `/dev/disk/by-label` 的系统可以通过 `lsblk -f` 查找卷标，使用对应设备挂载，勿假定永远是 `/dev/vdb`。
- 无法挂载：Guest 需要 ISO9660 支持，root 可尝试 `modprobe isofs`（普通用户加 sudo）。
- vsock 检查失败：需要内核支持 virtio-vsock。仅安装软件无法补齐缺失的内核功能。
- 服务 active 不代表图形链路已可用：需要 virtio-gpu、DRM debugfs，以及采集器支持的活动 KMS 输出。安装器不安装桌面，不创建第二个图形会话。
- 检测到已有 ttyd 服务或端口被占用时停止安装 ttyd；保留现有服务可用 `--skip-ttyd`。服务覆盖安装不清理你另外手动启动的进程。

更详细的配置见 [ttyd](CUSTOM-TTYD.md) 和 [图形采集](GUEST-SCREEN-SERVICE.md)。本安装器不调用 AOSP init.sh，也不安装 AOSP Guest agent 或自动扩容磁盘。

## 构建工具盘

安装 Python 依赖 `pycdlib` 后执行：

```sh
python3 tools/build-guest-tools-iso.py
python3 tools/verify-guest-tools-iso.py
```

ISO 内文件直接来自仓库的安装器和 Guest 源码。修改这些文件后必须重新生成工具盘。APK 验证同时检查内置 ISO 和源文件的一致性摘要，防止打包陈旧安装器。
