# 虚拟机屏幕采集服务

本项目显示的是来宾现有 DRM/KMS 输出，不会另开一个 VNC/RDP 桌面。
数据经 AVF vsock 传到 App，再由 Android 渲染；键盘、触摸和鼠标通过 AVF 输入接口回传。
采集需要 **Linux 来宾内的管理员权限**，不需要 Android 手机 root。

## 预构建 Debian：通常无需手工操作

当前 App 会自动为受管理的 Debian 镜像安装、更新并启用
`terminal-plus-capture.service`。服务随虚拟机开机启动，不再有临时版的 30 分钟限制。
已有镜像通过独立的本地 ttyd 会话运行固定安装程序；使用 `sudo -n`，不会询问或保存密码，
也不会重置磁盘、修改用户或设置自动登录。

自动配置依赖预构建 Debian 原有的 Python 3、systemd、ttyd 和非交互 sudo 权限。
如果自行修改了这些条件，可以按下方方式手工安装。自定义镜像不自动执行安装。

**服务已经安装但没启动时，在虚拟机终端执行这一条命令：**

```sh
sudo systemctl daemon-reload && sudo systemctl enable --now terminal-plus-capture.service && sudo systemctl restart terminal-plus-capture.service
```

返回图形页后会自动重连，不必重装 App 或删除虚拟机。

## 自定义镜像 / 手工安装

需要 Linux DRM/KMS、vsock、Python 3、systemd，以及可读取的
`/sys/kernel/debug/dri/0/state` 和 `/dev/dri/card0`。
目前支持线性 XR24/AR24 帧缓冲，以及常见 ARGB 硬件光标；不是所有 GPU 格式、
多显示器组合或保护内容都能捕获。可选的 `liblz4` 能加快传输，缺少时会回退。

先把包含图形采集功能的分支（如 `codex/feat-aosp-kms-display`）源码放入**虚拟机内**。
私有 GitHub 仓库需要你自己的访问权限，
不要把访问令牌写进安装脚本。随后在仓库根目录执行：

```sh
sudo sh tools/install-guest-capture.sh
```

不想复制整个仓库，也可以把以下三个文件放到虚拟机同一目录：

- [install-guest-capture.sh](../tools/install-guest-capture.sh)
- [terminal-plus-capture.py](../app/src/main/assets/guest-setup/terminal-plus-capture.py)
- [terminal-plus-capture.service](../app/src/main/assets/guest-setup/terminal-plus-capture.service)

在该目录一键安装：

```sh
sudo sh ./install-guest-capture.sh .
```

安装器会检查依赖、安装文件并启用服务；不会自动安装系统软件包或改写账号。
如果 debugfs 尚未挂载，先确认内核支持，再执行：

```sh
sudo mount -t debugfs debugfs /sys/kernel/debug
```

自定义系统还需保证重启后 debugfs 可用。无 systemd 的镜像可直接运行采集程序，
但需要用自己的 init 系统管理开机启动：

```sh
sudo python3 /usr/local/bin/terminal-plus-capture.py --fps 30
```

## 状态、日志与停止

```sh
systemctl is-enabled terminal-plus-capture.service
systemctl is-active terminal-plus-capture.service
sudo journalctl -u terminal-plus-capture.service -n 50 --no-pager

# 停止并取消自启
sudo systemctl disable --now terminal-plus-capture.service
```

对于 App 管理的 Debian，下一次 App 自动配置可能再次启用服务；上面的停止命令主要用于排查。
采集只监听 vsock 7683，只接受宿主 CID 2，不开放局域网 TCP 端口。

## 能显示但画面不更新

先检查图形会话是否活动。原版 AOSP 的 shell 显示启动脚本可能与后来安装的 GDM 等
显示管理器争用 tty1。仅在确认遇到这个兼容问题时，运行：

```sh
sudo python3 tools/experiments/fix-aosp-display-seat.py
```

这个工具会备份已知 AOSP 脚本，避免已启用显示管理器时重复创建空的显示会话，
并尝试激活明确的图形会话。它不修改密码或自动登录设置；不是通用自定义镜像安装步骤。

## 代码位置与维护

- [采集器源代码](../tools/experiments/kms-capture-server.py)：读取活动输出、合成光标、编码和 vsock 服务。
- [systemd 服务](../guest/root_files/etc/systemd/system/terminal-plus-capture.service)。
- [App 自动配置](../app/src/main/java/com/android/virtualization/terminal/GuestScreenSetup.kt)。
- [App 显示接收与绘制](../app/src/main/java/com/android/virtualization/terminal/KmsDisplayProvider.kt)。
- [打包脚本](../tools/build-cidata.py)：将同一份采集器生成到 cidata 和 App 升级资源。

修改采集器或服务后执行 `.venv/bin/python tools/build-cidata.py`，不要单独改生成副本。
协议、性能测量和已知限制见 [KMS-CAPTURE 实验记录](experiments/KMS-CAPTURE.md)。
