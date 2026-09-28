# 虚拟机管理与自定义启动

“设置 → 虚拟机”位于设置首项，统一管理磁盘、启动方式和每台 VM 的配置。
没有安装系统时，初始页面提供下载 Android 官方预构建 Debian 或导入自定义镜像；已有系统原地保留。

## 导入

先选择导入模式，再通过系统文件选择器选择文件：

- **Debian 镜像包**：Android 官方格式 `images.tar.gz`（也可使用同格式的其他文件名）。包根目录需包含 `vm_config.json`、`root_part` 和配置引用的内核等文件。App 流式解压、创建独立 VM，沿用包内资源与启动配置，默认进入 ttyd；不需要另选 U-Boot 或内核。缺失 cidata 时补入 App 内置版本。拒绝越界路径、链接、重复条目及引用外部 VM 文件的配置。
- **IMG / RAW 磁盘镜像**：支持 `.img`、`.raw` 和 gzip 压缩磁盘（如 `.img.gz`），按内容识别压缩格式。选择以下启动方式：

- **U-Boot**：ARM64 raw 可启动整盘镜像。默认复制 `/apex/com.android.virt/etc/u-boot.bin`；无法读取时使用 APK 内置版本，也可自行选择替代文件。
- **直接内核**：选择 ARM64 Linux 内核、可选 initrd、填写内核参数。支持整盘或文件系统镜像，例如 root 参数分别可能是 `/dev/vda1` 或 `/dev/vda`。内核需支持设备上的 AVF/crosvm，App 不对未知自定义内核自动打补丁。

磁盘镜像模式默认 2 GiB、CPU 匹配宿主；导入后可修改。默认进入串口控制台，保留 Guest 原有登录方式。
不直接支持 qcow2、ZIP/XZ 压缩或 ISO。Debian 发行版提供的单独压缩磁盘不是 Android Debian 镜像包，应使用磁盘镜像模式。格式检查通过不等于该系统一定兼容或能完成引导。

源文件不变，App 创建私有副本；失败或取消会清理未完成的副本，零数据区域保留为稀疏文件。
已导入的 VM 保持自己的 U-Boot，App 升级不自动替换它。

## 切换、长按菜单

点击条目切换；运行中的系统应先正常关机。界面也提供明确的“强制停止并切换”，未保存的工作会丢失。
停止后不会自动删除磁盘，启动失败也可以返回管理页切回其他 VM。

长按提供：

- **重命名**：只修改显示名称，内部 VM 标识不变。
- **克隆**：源 VM 关机后复制独立磁盘和配置。官方 Debian 也可克隆；利用文件系统空洞避免把大容量稀疏磁盘展开成实体空间。进度按已处理的逻辑字节统计。
- **删除**：关机后再次确认，永久删除该 VM 的私有磁盘和配置，外部源文件与其他 VM 不变。默认 Debian 删除后可重新下载。

普通克隆是完整复制，不是在线快照。不要在 VM 运行时从 App 外修改磁盘。

## 每台 VM 的配置

条目右侧“配置”可设置默认打开 ttyd、控制台或图形界面，修改内存与 CPU，或编辑 `vm_config.json`。
资源表单直接编辑同一份 JSON。保存后下次启动生效，不再被旧的全局内存偏好覆盖。
当前 CPU 支持 `one_cpu` / `match_host`，尚不提供未经设备验证的任意 vCPU 数值。

保存检查 JSON、字段、内存范围、启动方式、文件可读性和可写磁盘边界。
`kernel` 与 `bootloader` 必须二选一；U-Boot 模式不能另外指定 initrd。
`$PAYLOAD_DIR` 指当前 VM 的磁盘资源目录，不指向默认 Debian。
可写磁盘必须在当前 VM 目录内；内部 `name` 固定，显示名称通过重命名修改。
`console_out=true`、`connect_console=false` 是 App 控制台工作的要求。
AOSP 旧模板的 `platform_version` 不由这里的 Java Builder 应用，迁移时移除，编辑器不接受该字段。

“恢复上次启动／保存的配置”只把历史配置载入草稿，确认保存才生效，不回滚磁盘。
“上次启动”指 AVF 成功执行 `run()`，并不保证 Guest 已完成启动或登录。
图形页面仍根据窗口及显示分辨率设置动态更新显示尺寸；共享目录仍受 Android 文件访问权限限制。

原默认磁盘保持 `files/linux`。配置与显示偏好位于 `files/virtual-machines/<ID>/`，自定义文件也位于其目录；官方克隆的磁盘在 `payload/`。

## Guest 工具

自定义镜像的 ttyd 临时启动命令、systemd 配置和认证说明见 [接入 ttyd](CUSTOM-TTYD.md)。

没有 ttyd、Guest agent 或图形服务不会停止 VM。自定义系统不会自动安装或更改这些服务。
图形采集和端口代理统一为 `terminal-plus-guest.service`，安装方法见 [Guest 服务](GUEST-SCREEN-SERVICE.md)。

## U-Boot credit

内置版本取自开发设备的 Android Virtualization APEX；版本、SHA256、GPL-2.0 许可和精确源码链接见 [U-Boot 来源](../third_party/u-boot/README.md)。

## 串口控制台

控制台与 ttyd 操作同一台 VM，使用不同 shell 会话。保留 Guest 原有登录流程；App 保存缩放后的字号，但不自动同步 Guest TTY 行列数。需要时手动执行 `stty rows 90 cols 60`，或使用可协商窗口尺寸的 ttyd / SSH。

关闭最后一个 ttyd 标签后保留空白提示页和工具栏，不退出、不停止 VM，也不自动新建标签。
