# Guest 图形采集与端口代理

适用于 App 虚拟显示器页面。以下命令在 **Guest 内**执行，示例面向 Debian/Ubuntu；不需要 Android 宿主 root。

当前图形路径：

```text
Guest virtio-gpu DRM/KMS 画面与光标
  → terminal-plus-capture.py
  → vsock 7683
  → App 渲染器与 AOSP 显示操作界面
```

`terminal-plus-guest.service` 监管两个独立进程：

| 模块 | 通道 | 作用 |
| --- | --- | --- |
| 图形采集 | vsock 7683 | 读取现有 DRM/KMS 画面与光标 |
| 端口代理 | vsock 7682 | 访问 Guest localhost TCP 服务 |

模块异常后分别重启。端口代理降权到已有的 droid 或 nobody 用户；缺失 DRM 不影响代理运行。两个模块仅接受宿主 CID 2 的请求，不在局域网监听 TCP 端口。

此服务不安装桌面、不启动第二个图形会话，也不提供 ttyd 或串口尺寸同步。ttyd 的安装与认证说明见 [接入 ttyd](CUSTOM-TTYD.md)。触摸、鼠标和键盘由 App 的 AVF 输入通道处理，不由采集脚本注入。

## 准备条件

- Python 3，以及 Guest root 或 sudo 权限。
- Guest 内核支持 virtio-vsock、virtio-gpu 和 DRM debugfs。
- `/dev/dri/card0` 上有活动的、采集器支持的 DRM/KMS 输出。
- 开机启动示例使用 systemd。推荐安装 `liblz4-1` 加速无损传输；缺失时回退到 zlib/原始帧。

```sh
sudo apt update
sudo apt install python3 liblz4-1
ls -l /dev/dri/card0
mountpoint -q /sys/kernel/debug || sudo mount -t debugfs debugfs /sys/kernel/debug
sudo ls /sys/kernel/debug/dri
```

手动挂载只影响当前启动；重启后若发行版没有自动挂载 debugfs，需要通过该发行版的挂载配置保持可用。只有串口输出、没有活动 KMS 画面的系统不会因为安装此服务而产生图形桌面。

## 安装或更新

App 管理且匹配已知 cidata 版本的官方 Debian，可通过可用的认证 ttyd 通道自动更新服务；自定义镜像需要手动安装。以下方式任选其一。

**已经把仓库放入 Guest：** 在仓库根目录执行：

```sh
sh tools/install-guest-tools.sh
```

**使用离线安装包：** 将仓库中的 [guest-tools.bundle](../app/src/main/assets/guest-setup/guest-tools.bundle) 复制到 Guest，在它所在目录执行：

```sh
mkdir -p terminal-plus-tools
tar -xzf guest-tools.bundle -C terminal-plus-tools
sh terminal-plus-tools/install-guest-tools.sh terminal-plus-tools
```

`.bundle` 内部是 gzip 压缩 tar。此后缀用于避免 Android 打包器自动解压改名。私有仓库需要认证后获取，不能把未经认证的 raw URL 当作公开下载地址。

安装器会安装脚本和下方同款 systemd 单元、启用开机启动，并在内容变化时重启服务。它会停用旧的 capture/proxy/console-resize/kms-probe 单元，清理旧尺寸同步脚本；不修改账户、密码、ttyd 或 SSH。

## 手动配置 systemd

如果希望查看或自行部署完整配置，可用这一节替代上面的安装器。先将 `guest-tools.bundle` 放到当前目录；这里的命令只安装 Guest 工具，不下载或修改系统镜像。

```sh
mkdir -p terminal-plus-tools
tar -xzf guest-tools.bundle -C terminal-plus-tools

sudo install -d -m 755 /usr/local/bin
sudo install -m 755 terminal-plus-tools/terminal-plus-guest.py /usr/local/bin/terminal-plus-guest.py
sudo install -m 755 terminal-plus-tools/terminal-plus-capture.py /usr/local/bin/terminal-plus-capture.py
sudo install -m 755 terminal-plus-tools/terminal-plus-proxy.py /usr/local/bin/terminal-plus-proxy.py

sudo tee /etc/systemd/system/terminal-plus-guest.service >/dev/null <<'UNIT'
[Unit]
Description=Terminal Plus guest integration
After=local-fs.target network.target

[Service]
ExecStart=/usr/bin/python3 -u /usr/local/bin/terminal-plus-guest.py
Restart=on-failure
RestartSec=2
KillMode=control-group
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
RestrictAddressFamilies=AF_VSOCK AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
UNIT

# 迁移旧版 Plus 单元，避免多个进程争用相同端口。
for name in capture console-resize proxy kms-probe; do
    sudo systemctl disable --now "terminal-plus-$name.service" 2>/dev/null || true
done
sudo rm -f /usr/local/bin/terminal-plus-console-resize.py

sudo systemctl daemon-reload
sudo systemctl enable terminal-plus-guest.service
sudo systemctl restart terminal-plus-guest.service
systemctl status terminal-plus-guest.service --no-pager
```

单元有意不设置 `User=`，因为读取 compositor 的 DRM 缓冲区需要 Guest 管理员权限。统一管理进程只为端口代理子进程降权。`KillMode=control-group` 确保停止服务时一并结束子进程。

已有采集服务运行时，不要另外手动启动第二个采集进程。无 systemd 的系统可在脚本安装后临时运行：

```sh
sudo python3 -u /usr/local/bin/terminal-plus-guest.py
```

此命令在前台运行，按 Ctrl+C 结束；开机启动需接入该发行版的服务管理器。

## 使用与维护

返回 App，点击虚拟显示器按钮。可在 VM 配置中将“启动后打开”设为图形；画面加载失败不会结束 VM。

```sh
sudo systemctl restart terminal-plus-guest.service
systemctl is-enabled terminal-plus-guest.service
systemctl is-active terminal-plus-guest.service
sudo journalctl -u terminal-plus-guest.service -n 50 --no-pager

# 临时停止；下次开机仍启动
sudo systemctl stop terminal-plus-guest.service
# 取消开机启动并立即停止；需要恢复时执行 enable --now
sudo systemctl disable --now terminal-plus-guest.service
```

停止统一服务也会停止端口代理，不会停止 VM 或 ttyd。

## 排查

**服务 active，但没有画面：** active 只说明管理进程存活，子进程可能在重试。查看日志，确认 `/dev/dri/card0`、debugfs 和活动 KMS 输出。当前支持测试过的线性 XR24 主平面和标准线性 ARGB 光标；通用叠加平面、平铺缓冲区等格式不保证支持。

**端口冲突：** 停用旧的采集/代理单元或临时进程，再启动统一服务。不要同时运行安装器部署的服务与前台 Python 命令。

**画面有刷新，但桌面不变化：** 若使用 AOSP Debian 的 GDM/GNOME，旧 `activate_display.sh` 可能抢占图形会话的 seat。仅确认该原因后，在 Guest 仓库根目录执行：

```sh
sudo python3 tools/experiments/fix-aosp-display-seat.py
```

脚本会备份原文件，不设置自动登录或修改密码。原因和历史验证见 [KMS 实验记录](experiments/KMS-CAPTURE.md)。

**帧率低：** 先降低 App 显示分辨率，确认已安装 `liblz4-1`，再查看 Guest 负载。采集、压缩传输与 App 绘制都有开销；GPU 后端存在不等于来宾硬件 3D 加速已验证，也不保证稳定 30/60 fps。

## 实现和来源

- [统一管理进程](../guest/root_files/usr/local/bin/terminal-plus-guest.py)、[systemd 单元](../guest/root_files/etc/systemd/system/terminal-plus-guest.service)
- [图形采集源码](../tools/experiments/kms-capture-server.py)、[端口代理](../guest/root_files/usr/local/bin/terminal-plus-proxy.py)
- [统一安装器](../tools/install-guest-tools.sh)、[cidata 和安装包生成器](../tools/build-cidata.py)

日常部署优先使用安装器，手动配置段保留完整可审阅的等价步骤。
