# 统一 Guest 服务：图形采集、串口尺寸与端口代理

只需要一个 `terminal-plus-guest.service`，管理三个独立模块：

| 模块 | 通道 | 作用 |
| --- | --- | --- |
| 串口尺寸 | vsock 7684 | 同步行列数，通过 `TIOCSWINSZ` 让前台 TUI 收到 `SIGWINCH` |
| 图形采集 | vsock 7683 | 采集 Guest DRM/KMS 画面与光标 |
| 端口代理 | vsock 7682 | 访问 Guest 的 localhost TCP 服务 |

各模块在独立进程中运行，避免图形采集的 CPU 工作阻塞尺寸更新。模块退出后单独重启。
服务只接受 Android 宿主 CID 2 的 vsock 请求，不在局域网监听 TCP 端口。
端口代理降权到已有的 droid 或 nobody 用户；没有可用用户时仅该模块不可用。

## 安装一次

**最方便的方法：点击 App 控制台底部“列 × 行”状态，复制安装命令，在 Guest 已登录的 shell 内自行粘贴执行。**
命令携带 App 内置版本，不要求 Guest 能访问 GitHub。需要 Python 3、systemd，以及 root 或 sudo 权限。
App 不会把安装命令自动输入到串口，也不改动登录账户或密码。

已经在 Guest 中取得仓库代码时，一条命令即可安装或更新：

```sh
sh tools/install-guest-tools.sh
```

私有仓库须通过自己的 GitHub 身份获取，不能使用未经认证的 raw 链接。
也可把 [guest-setup](../app/src/main/assets/guest-setup) 中的 `guest-tools.tar.gz` 拷入 Guest：

```sh
mkdir -p terminal-plus-tools
tar -xzf guest-tools.tar.gz -C terminal-plus-tools
sh terminal-plus-tools/install-guest-tools.sh terminal-plus-tools
```

安装器会停用旧的 `terminal-plus-capture`、`terminal-plus-proxy`、`terminal-plus-console-resize` 与临时 kms-probe 单元，避免端口冲突。
不会停用 ttyd、SSH 或发行版原有服务。旧入口 `tools/install-guest-capture.sh` 保留为统一安装器的兼容入口。

App 管理的官方 Debian 会通过已有认证 ttyd 通道自动更新此服务；自定义镜像需显式安装一次。
没有 ttyd、没有桌面也能使用串口尺寸同步，缺失 DRM 不影响其运行。

## 日常维护

```sh
sudo systemctl restart terminal-plus-guest.service
systemctl is-enabled terminal-plus-guest.service
systemctl is-active terminal-plus-guest.service
sudo journalctl -u terminal-plus-guest.service -n 50 --no-pager
```

服务 active 表示管理进程运行中，不代表 Guest 一定存在可采集的图形画面。
图形采集仍需要 virtio-gpu、`/dev/dri/card0` 与可读 DRM debugfs 状态。
需要时在 Guest 挂载 debugfs：

```sh
sudo mount -t debugfs debugfs /sys/kernel/debug
```

无 systemd 的系统，可将这些 Python 模块放在同一目录，以 root 运行 `python3 terminal-plus-guest.py`，自行接入该发行版的启动机制。

## 控制台尺寸

App 根据实际视图大小和等宽字体计算列数、行数，缩放、旋转、软键盘开关都触发更新。
变更合并后发送，Guest 启动期间连接失败会保留最新尺寸并重试；退出控制台停止同步。
当前支持 `/dev/hvc0` 和 `/dev/ttyS0`，根据 VM 的控制台配置选择，不猜测用户账户。
不通过键盘输入注入 shell 命令，不执行任意路径或任意指令。

检查 Guest 当前尺寸：

```sh
stty size
```

输出顺序是 **行 列**。App 显示“60 列 × 90 行”时，应输出 `90 60`。
不安装服务时可临时在 shell 运行 `stty rows 90 cols 60`，但下次尺寸变化需要重新执行。
U-Boot 自身不是 Linux TTY，不支持这里的 ioctl/SIGWINCH 同步。

## 图形画面不更新

若使用 GDM/GNOME，原 AOSP `activate_display.sh` 可能抢占图形会话的 seat。
只有确认该问题时才在 Guest 执行修复脚本（脚本会备份原文件）：

```sh
sudo python3 tools/experiments/fix-aosp-display-seat.py
```

它不会设置自动登录或修改密码；详细排查见 [KMS 实验记录](experiments/KMS-CAPTURE.md)。

## 实现和来源

- [统一管理进程](../guest/root_files/usr/local/bin/terminal-plus-guest.py)、[systemd 单元](../guest/root_files/etc/systemd/system/terminal-plus-guest.service)
- [串口尺寸模块](../guest/root_files/usr/local/bin/terminal-plus-console-resize.py)、[App 尺寸发送器](../app/src/main/java/com/android/virtualization/terminal/ConsoleResizeClient.kt)
- [图形采集源码](../tools/experiments/kms-capture-server.py)、[端口代理](../guest/root_files/usr/local/bin/terminal-plus-proxy.py)
- [统一安装器](../tools/install-guest-tools.sh)、[生成 cidata 和安装包](../tools/build-cidata.py)

串口尺寸通知路线参考了 [Podroid](https://github.com/ExTV/Podroid) 的 resize-notifying session / Guest control channel 设计；这里的协议与服务为独立实现，没有复制其实现代码。
