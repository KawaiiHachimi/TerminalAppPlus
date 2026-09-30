# AlmaLinux 在 AVF 中的网络兼容问题

2026-09-30 实测环境：OPPO PKB110 / MT6991 / GenieZone，AlmaLinux 10.2 ARM64，内核 `6.12.0-211.47.1.el10_2.aarch64`，cloud-init `24.4-7.el10_2.1`，NetworkManager `1.56.0-2.el10_2`。

下面记录两个独立问题和手动处理办法，仅适用于出现相同症状的 Guest。不能推断所有 AlmaLinux、Rocky 或 RHEL 镜像都会受影响。App、CIDATA 和 Guest Tools 不自动部署这里的 workaround。

## 1. cloud-init 配置没有匹配接口

当前 CIDATA 使用 `match.name: en*`。本次 AlmaLinux 的主接口名为 `eth0`，`enp0s13` 只是备用名称；其 cloud-init / NetworkManager 渲染路径把配置 ID `avf` 写成了 `interface-name=avf`，没有保留名称通配匹配。于是 `cloud-init avf` 无法启用，默认的 `Wired connection 1` 尝试接管。

先确认实际接口和连接名称：

```sh
ip -br link
nmcli --ask -f NAME,TYPE,DEVICE connection show
sudo cat /etc/NetworkManager/system-connections/cloud-init*.nmconnection
```

如果与上述情况一致，可备份并修改已有连接，不必重新运行整个 cloud-init：

```sh
sudo cp -a /etc/NetworkManager/system-connections \
  "/root/network-connections-backup-$(date +%s)"
sudo nmcli connection modify 'cloud-init avf' \
  connection.interface-name eth0 connection.autoconnect yes \
  ipv4.method auto ipv6.method disabled
sudo nmcli --wait 45 connection up 'cloud-init avf'
```

连接名和接口名以实际输出为准。测试时，明确的 `eth0` 配置可以正确生成并加载，但仍发生 DHCP 超时，原因是下面的校验和异常。不要把接口匹配成功等同于已经联网。

## 2. DHCP / DNS 响应的 UDP 校验和异常

证据：

- Guest 抓得到网关发来的 DHCP OFFER，但 UDP 校验和验证失败；`PACKET_AUXDATA` 未携带 `CSUMNOTREADY` 标记。
- NetworkManager 获取地址超时，另一客户端 dhcpcd 也报告 `checksum failure`。
- 只补算 DHCP 响应的 UDP 校验和后，NetworkManager 立即得到 IPv4 和默认路由。
- 随后 DNS 查询仍失败，`UdpInCsumErrors` 增长；对 DNS 响应做相同处理后，域名解析成功。

这些结果指向 Android / crosvm / virtio 网络链路的校验和卸载兼容性，尚未定位丢失信息的具体层。关闭 wait-online 不能修复此问题，`ethtool -K eth0 rx off` 在本设备上也无效（RX checksum 为 fixed）。

### 手动 workaround

需要 Guest 已有 `/sbin/tc`，内核支持 `clsact`、`cls_u32` 和 `act_csum`。以下示例仅匹配 `eth0` 上的 DHCP、DNS 响应，不接管 DHCP，不设置静态地址，不关闭网络管理器。

先检查现有 ingress 规则：

```sh
sudo tc qdisc show dev eth0
sudo tc filter show dev eth0 ingress
```

下面使用优先级 `49152` 和 `49153`；若已被其他规则占用，应另选未用值，并同步调整移除命令。补算校验和是针对已确认异常链路的临时兼容措施，不应对正常网络普遍启用。

创建独立开机服务，在 NetworkManager 启动前应用规则：

```sh
sudo tee /etc/systemd/system/terminal-plus-dhcp-checksum.service >/dev/null <<'UNIT'
[Unit]
Description=Complete AVF DHCP and DNS reply checksums on eth0
Requires=sys-subsystem-net-devices-eth0.device
After=sys-subsystem-net-devices-eth0.device
Before=NetworkManager.service

[Service]
Type=oneshot
ExecStart=-/sbin/tc qdisc add dev eth0 clsact
ExecStart=/sbin/tc filter replace dev eth0 ingress protocol ip pref 49152 u32 match ip protocol 17 0xff match ip sport 67 0xffff match ip dport 68 0xffff action csum udp
ExecStart=/sbin/tc filter replace dev eth0 ingress protocol ip pref 49153 u32 match ip protocol 17 0xff match ip sport 53 0xffff action csum udp
RemainAfterExit=yes

[Install]
WantedBy=NetworkManager.service
UNIT
sudo restorecon /etc/systemd/system/terminal-plus-dhcp-checksum.service
sudo systemctl daemon-reload
sudo systemctl enable --now terminal-plus-dhcp-checksum.service
sudo nmcli --wait 45 connection up 'cloud-init avf'
```

若另行手动启动过 dhcpcd，先停止那一实例，避免与 NetworkManager 同时管理接口。本次测试中新建连接名是 `cloud-init eth0`；使用该连接时替换最后一行的名称。

### 验证与限制

```sh
ip -4 address show dev eth0
ip -4 route
getent ahostsv4 deb.debian.org
sudo tc -s filter show dev eth0 ingress
sudo systemctl status terminal-plus-dhcp-checksum.service --no-pager
```

已实测恢复 DHCP 地址、默认路由、外网 IP 连通及 DNS 解析；开机服务已写入并启用，但本次记录未完成重启后的复验。规则不修复宿主或内核的根本问题，也不代表其他 UDP 流量已验证。

### 移除 workaround

```sh
sudo systemctl disable --now terminal-plus-dhcp-checksum.service
sudo rm /etc/systemd/system/terminal-plus-dhcp-checksum.service
sudo systemctl daemon-reload
sudo tc filter del dev eth0 ingress protocol ip pref 49152
sudo tc filter del dev eth0 ingress protocol ip pref 49153
```

保留 clsact，避免删除其他程序的 qdisc 或过滤规则。接口配置若需恢复，使用前面保存的连接备份。

相关说明：[cloud-init 初始化](CLOUD-INIT.md)、[Guest 工具盘](GUEST-TOOLS-ISO.md)。
