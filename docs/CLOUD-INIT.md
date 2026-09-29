# 初始配置（cloud-init，实验）

仅用于自定义 img/raw/qcow2 磁盘镜像。镜像本身必须预装 cloud-init，并支持 NoCloud；文件格式不代表具备初始化能力。官方格式 images.tar.gz 沿用原有 cidata，不应用此功能。

## 使用

在导入页启用“初始配置（cloud-init）”，填写用户名、密码（两次确认）、可选主机名和 SSH 公钥。密码与公钥至少设置一项。普通用户授予需要密码验证的 sudo 权限；用户名为 root 时设置已有 root 账户。“允许 SSH 密码登录”默认关闭，与控制台密码登录分开。SSH root 登录仍受镜像的 sshd/PAM 策略限制。

App 将生成独立、只读、卷标为 `CIDATA` 的 ISO，包含 `user-data` 和 `meta-data`，首次启动由 Guest 读取。它与 `PLUS_TOOLS` 工具盘独立，不安装 ttyd、桌面或采集服务。默认不写网络配置，使用镜像/cloud-init 原有的网络初始化行为。

首次启动前，可在“虚拟机 → 配置 → 初始配置”修改或关闭。密码不回显，留空保留已保存密码；“保存初始配置”立即保存配置盘，与资源配置的保存按钮独立。密码仅以加盐 SHA-512 crypt 哈希写入私有目录中的 ISO，不保存明文；配置盘仍应视作敏感数据。

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

已验证密码哈希、稳定 instance-id、输入校验、首次启动锁定、模板文件读取以及 cloud-init 官方 schema；不同发行版首次启动仍需实测。

参考：[NoCloud 数据源](https://docs.cloud-init.io/en/latest/reference/datasources/nocloud.html)。
