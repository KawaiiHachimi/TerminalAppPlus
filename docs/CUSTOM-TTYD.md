# 自定义镜像接入 ttyd

适用于通过 U-Boot 或直接内核启动的自定义 Linux 镜像。以下命令在 **Guest 内**执行，示例面向 Debian/Ubuntu 和 Fedora/RHEL 系；不会修改 Android 宿主。

当前 App 的连接路径：

```text
App WebView
  → Android 本地 HTTP 桥接（随机端口，验证 access_token Cookie）
  → AVF connectVsock(7681)
  → Guest socat
  → Guest ttyd
```

当前自定义 VM 通道使用 HTTP/WebSocket，不使用 TLS 证书。随机 Cookie 在 App 桥接层校验，不需要将 App 证书或 token 配置到 Guest。旧版 HTTPS/mTLS 路线中，客户端证书由 Guest 服务端验证；不要将两种配置混用。

Guest 内核必须支持 virtio-vsock。只在 Guest 开放 TCP 7681 不够，App 连接的是 **vsock 7681**。此功能不依赖 AOSP Guest agent、图形采集或 `terminal-plus-guest.service`；后者目前只监管采集和端口代理，不安装 ttyd。

## 准备条件

- Guest 已能启动并登录普通用户，有 root 或 sudo 权限用于安装软件。
- Guest 内核支持 virtio-vsock；socat 由包管理器安装，ARM64 ttyd 可以直接使用工具盘自带版本。
- 开机启动示例使用 systemd。自定义镜像需要手动配置，App 不会自动创建账户。

建议使用 [Guest 工具盘的一键安装](GUEST-TOOLS-ISO.md)，自动选择 ttyd 路径并配置开机启动。以下仅供手动部署。

## 临时启动

先在串口或 SSH 登录你想用于终端的普通用户，再执行：

```sh
sudo apt update
sudo apt install socat
# RPM 系改用：sudo dnf install socat
# ARM64 Guest，先挂载 PLUS_TOOLS（已挂载可跳过）：
sudo mkdir -p /mnt/plus
sudo mount -o ro LABEL=PLUS_TOOLS /mnt/plus
# 若已有 ttyd，则保留原来的可执行文件。
command -v ttyd >/dev/null || sudo install -m 755 /mnt/plus/ttyd.aarch64 /usr/local/bin/ttyd

nohup ttyd -i 127.0.0.1 -p 7681 -W bash -l \
  >"$HOME/ttyd.log" 2>&1 &

nohup socat VSOCK-LISTEN:7681,fork,reuseaddr \
  TCP:127.0.0.1:7681 \
  >"$HOME/ttyd-vsock.log" 2>&1 &
```

返回 App，打开 ttyd 标签或点击重试。需要时在该 VM 的配置中将“启动后打开”设为 ttyd。

- `-W` 允许键盘输入，`bash -l` 启动当前用户的登录 shell。
- 用户名不要求为 `droid`。不要用 `sudo ttyd ... bash`，除非你明确需要 root shell。
- 不加 `-S`（HTTPS）：当前 App 桥接预期普通 HTTP。
- TCP 与 vsock 是不同协议，两边使用 7681 不冲突。TCP 只绑定 Guest 回环地址。
- 这些进程不会在 Guest 重启后自动恢复。

默认 Android Debian 初始化配置使用 `droid` / `droid`；这不适用于任意自定义发行版镜像。串口输入密码时不回显字符。ttyd 的 shell 用户身份由启动命令决定，不等同于 HTTP 或 TLS 认证。

## systemd 开机启动

先结束上面临时启动的两个进程，释放端口，再配置服务。若已存在 AOSP 的 `ttyd_uds` / `ttyd_vsock_bridge`，使用原有服务即可，不要重复启动。

用 `command -v ttyd`、`command -v socat` 确认安装路径。下面按工具盘安装的 `/usr/local/bin/ttyd` 和仓库提供的 `/usr/bin/socat` 编写；复用已有 ttyd 时按实际路径调整。

创建 `/etc/systemd/system/terminal-plus-ttyd.service`，将所有 `YOUR_USER` 替换为已有普通用户名，并按实际情况修改其家目录：

```sh
sudo tee /etc/systemd/system/terminal-plus-ttyd.service >/dev/null <<'UNIT'
[Unit]
Description=Terminal Plus ttyd
After=local-fs.target

[Service]
User=YOUR_USER
WorkingDirectory=/home/YOUR_USER
Environment=HOME=/home/YOUR_USER
Environment=TERM=xterm-256color
ExecStart=/usr/local/bin/ttyd -i 127.0.0.1 -p 7681 -W /bin/bash -l
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
```

创建 `/etc/systemd/system/terminal-plus-ttyd-vsock.service`，同样替换 `YOUR_USER`：

```sh
sudo tee /etc/systemd/system/terminal-plus-ttyd-vsock.service >/dev/null <<'UNIT'
[Unit]
Description=Terminal Plus ttyd vsock bridge
Wants=terminal-plus-ttyd.service
After=terminal-plus-ttyd.service

[Service]
User=YOUR_USER
ExecStart=/usr/bin/socat VSOCK-LISTEN:7681,fork,reuseaddr TCP:127.0.0.1:7681
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
UNIT
```

启用并查看状态：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service
systemctl status terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service --no-pager
```

这两个服务专门处理 ttyd，与现有 `terminal-plus-guest.service` 独立。此处提供配置示例，不代表 App 已自动安装或合并这些服务。

## 使用与维护

```sh
sudo systemctl restart terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service
# 临时停止；取消开机启动可将 stop 改为 disable --now
sudo systemctl stop terminal-plus-ttyd-vsock.service terminal-plus-ttyd.service
```

停止 ttyd 会断开网页终端，不会停止 VM、串口或图形采集。恢复时执行 `sudo systemctl start terminal-plus-ttyd.service terminal-plus-ttyd-vsock.service`。

## 排查

```sh
# Guest 内确认 ttyd HTTP 服务；收到正常页面仅证明 HTTP 可用，不代表 WebSocket 已连通。
curl -I http://127.0.0.1:7681/

# systemd 模式
journalctl -u terminal-plus-ttyd.service -u terminal-plus-ttyd-vsock.service -n 50 --no-pager

# 临时模式
cat "$HOME/ttyd.log" "$HOME/ttyd-vsock.log"
```

- `Address already in use`：已有进程占用相应 TCP 或 vsock 端口，避免重复启动。
- vsock 不支持：可尝试 `sudo modprobe vmw_vsock_virtio_transport`；若内核未启用该驱动，需要换用支持 AVF/crosvm virtio-vsock 的内核。驱动内建时无需加载模块。
- HTTP 正常但 App 失败：检查 socat 日志、vsock 支持、是否误开 HTTPS，再点击 App 重试。Guest 服务 active 不保证整条链路可用。
- 这里保证的是服务接入方式，不保证任意发行版提供的 ttyd 前端与 App 的 AOSP WebView 集成完全兼容。当前 App 会使用前端的 `window.term` 和 xterm DOM；若换用了不同前端，仍需单独验证。

## 实现和来源

参数说明见 [ttyd 官方 README](https://github.com/tsl0922/ttyd/blob/main/README.md)。AOSP 预构建镜像使用的另一种等价连接是 vsock → Unix socket → ttyd；本文用回环 TCP 便于直接使用发行版软件包和排查。
