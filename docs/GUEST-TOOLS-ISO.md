# 自定义镜像 Guest 工具盘（实验）

适用于 Debian/Ubuntu、systemd，以及支持 virtio-vsock 的 Guest 内核。App 在启动自定义 img/raw 虚拟机时，将只读 `PLUS_TOOLS` ISO 追加到已有磁盘之后，支持 U-Boot 和直接内核启动；不改变原系统磁盘顺序，不修改保存的 vm_config.json。官方格式镜像包继续使用原来的 cidata 路线。

## 首次安装

更新 APK 后，先在 Guest 内正常关机，再重新启动虚拟机，使新增磁盘生效。在 App 的“设置 → 虚拟机 → 配置 → Guest 工具”可以复制安装命令。

在 Guest 控制台登录后（支持 root 和普通用户）执行：

root 执行：

```sh
mkdir -p /mnt/plus && mount -o ro LABEL=PLUS_TOOLS /mnt/plus && sh /mnt/plus/install.sh
```

普通用户在 App 中启用“使用 sudo”，复制的命令为：

```sh
sudo sh -c 'mkdir -p /mnt/plus && mount -o ro LABEL=PLUS_TOOLS /mnt/plus && sh /mnt/plus/install.sh'
```

`/mnt/plus` 为专用工具盘目录，勿用于其他挂载。如果工具盘已经挂载，只执行 `sh /mnt/plus/install.sh`（普通用户加 sudo）。安装器自动读取当前用户或 SUDO_USER，支持 root；指定其他用户可用 `--user USER`，已有 ttyd 时可用 `--skip-ttyd`。这些参数加在 install.sh 后，使用 sudo sh -c 时放在单引号内。

脚本按需安装依赖、配置 ttyd 和图形采集/端口代理服务并启用开机启动。缺少依赖时需要联网。本次新安装 ttyd 软件包启动的默认服务会被停用以避免端口冲突；原有 ttyd 服务保留。脚本不创建账户或修改密码。

## 后续检查、修复与更新

成功安装后，Guest 内保存一份工具和安装器，无需再次挂载 ISO：

```sh
terminal-plus-setup
terminal-plus-setup --force
```

第一条检查安装版本与服务状态，同版本不重复安装或重启服务；第二条使用本地安装包覆盖安装。普通用户会自动通过 sudo 提权。已安装的终端用户和是否安装 ttyd 的选择会保留，除非显式使用 `--user` 或 `--skip-ttyd`。

App 提供新版工具盘时，正常关闭 Guest 再启动，重新挂载并执行盘上的 install.sh 来更新；本地修复命令不会自行下载新版本。覆盖安装会重启 ttyd，请在控制台执行。

安装器仅检查 systemd 服务状态，active 不代表图形已成功采集或 App 已连接。脚本不自动执行 AOSP init.sh、不安装桌面或 AOSP Guest agent、不自动扩容磁盘。

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
