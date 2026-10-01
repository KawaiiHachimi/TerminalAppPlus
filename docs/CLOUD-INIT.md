# 初始配置（cloud-init，实验）

仅用于自定义 img/raw/qcow2 磁盘镜像。镜像本身必须预装 cloud-init，并支持 NoCloud；文件格式不代表具备初始化能力。官方格式 images.tar.gz 沿用原有 cidata，不应用此功能。

## 使用

在导入页启用“初始配置（cloud-init）”，填写用户名、密码（两次确认）、可选主机名和 SSH 公钥。密码与公钥至少设置一项。普通用户授予需要密码验证的 sudo 权限；用户名为 root 时设置已有 root 账户。“允许 SSH 密码登录”默认关闭，与控制台密码登录分开。SSH root 登录仍受镜像的 sshd/PAM 策略限制。

App 将生成独立、只读、卷标为 `CIDATA` 的 ISO，包含 `user-data`、`meta-data` 和独立的 `network-config`，首次启动由 Guest 读取。它与 `PLUS_TOOLS` 工具盘独立，不安装 ttyd、桌面或采集服务。网络配置按 `en*` 匹配 AVF 网卡，仅启用 DHCPv4，不绑定 MAC、不重命名接口。由 cloud-init 在首次启动的网络初始化阶段交给镜像原有网络后端处理，不依赖安装 Guest Tools。

初始配置仅在添加虚拟机时提供，已有虚拟机的配置页不提供编辑入口。密码仅以加盐 SHA-512 crypt 哈希写入私有目录中的 ISO，不保存明文；配置盘仍应视作敏感数据。

## 生命周期与限制

- 每台新导入 VM 使用稳定的 instance-id，普通重启不更换。
- App 在尝试启动前锁定配置；“已启动过”不代表 Guest 已成功初始化。已有 VM 也不开放重置入口，修改账户请在 Guest 内操作。
- 未启动过的克隆生成新 instance-id；已启动过的克隆保留 seed 和初始化身份，不强制重跑 Guest 初始化。
- 重新导入一个已经用过的系统磁盘，仍可能带有 cloud-init 缓存；不能保证表单会重置其中的账户。
- 不修改镜像中的 SSH root 登录限制、GDM 登录策略或已有安全策略。公钥仅接受独立 key 行，不接受私钥或 authorized_keys 选项。

进入 Guest 后可检查：

```sh
cloud-init status --long
sudo cat /var/log/cloud-init.log
sudo cat /var/log/cloud-init-output.log
lsblk -f
```

无 cloud-init、禁用了 NoCloud、缺少 ISO9660 支持等情况，都可能使配置盘不生效。App 不会用串口自动登录或注入命令补救。

## 实现与验证

配置盘模板由 `tools/build-cloud-init-template.py` 生成，Android 在固定文件槽中写入带注释填充的 YAML（JSON 子集），保留 ISO9660/Rock Ridge 文件结构。表单禁止输入任意脚本。

已验证密码哈希、稳定 instance-id、输入校验、首次启动锁定、模板文件读取以及 cloud-init 官方 schema。AlmaLinux 10.2（cloud-init 24.4）已实测识别 NoCloud 配置盘、创建 droid 用户并通过密码登录。该设备上 NetworkManager 的 DHCP 获取仍有兼容性问题，初始化成功不代表网络就绪；其他发行版仍需实测。

参考：[NoCloud 数据源](https://docs.cloud-init.io/en/latest/reference/datasources/nocloud.html)。

## AVF 网卡匹配

Debian 13 genericcloud 中，默认 fallback 网络配置可能经 Netplan 生成 `PermanentMACAddress=...`。实测 AVF virtio 网卡的 `ethtool -P` 返回 `Permanent address: not set`，即使当前 MAC 相同也不能匹配，导致网卡 unmanaged。独立的 NoCloud `network-config` 避免这项永久 MAC 条件：

```yaml
version: 2
ethernets:
  avf:
    match:
      name: "en*"
    dhcp4: true
    dhcp6: false
    accept-ra: false
```

配置不指定 renderer，由发行版选择其网络后端。已有 VM 的 CIDATA 不会因升级 APK 自动改写；测试首次启动需重新导入未经初始化的原始镜像。Guest Tools 不修改网络配置。

AlmaLinux 等使用不同网络后端的镜像，可能存在接口匹配或 DHCP 校验和问题，见 [AlmaLinux 网络兼容排查](ALMALINUX-NETWORK.md)。处理办法仅供手动使用，不随 CIDATA 或 Guest Tools 自动安装。

## 编辑 user-data YAML

导入自定义磁盘镜像时启用 cloud-init，可在“账户设置”和 `user-data.yaml` 之间切换。
账户设置支持用户名、密码、主组、附加组、登录 Shell、sudo 规则、密码锁定、SSH 公钥及 SSH 密码登录。附加组用逗号分隔，sudo 规则每行一条；填 `false` 不为该用户生成 sudo 规则，留空不覆盖镜像默认行为。锁定密码登录不影响 SSH 公钥认证。

表单与 YAML 共用一份配置。修改 YAML 后，语法正确就同步到表单；语法未完成时保留原文、显示错误，并暂停表单编辑。

表单只修改对应字段，保留 `packages`、`runcmd`、`write_files`、用户自定义属性及其他用户。多用户配置编辑第一个明确包含 `name` 的用户；只有 `default` 或没有命名用户时使用 YAML 编辑。通过 YAML 语法树更新，保留未修改节点及其注释，但序列化可能调整缩进或引号。

新密码与确认输入一致后，在后台生成哈希并同步到 YAML；已有密码哈希不回显为明文。手写的密码配置仅在表单明确设置新密码时替换该用户的 `passwd` / `plain_text_passwd`。

支持 `#cloud-config` 映射格式，导入前检查 YAML 语法、重复键和大小；具体 cloud-init 字段是否受发行版支持仍由 Guest 决定。`meta-data` 的实例 ID 和独立 `network-config` 继续由 App 管理。

YAML 会原样写入 CIDATA，手写明文密码也会保留；优先使用 SSH 公钥或密码哈希。

YAML 解析使用 [SnakeYAML](https://github.com/snakeyaml/snakeyaml)（Apache-2.0），通过安全构造器加载。
