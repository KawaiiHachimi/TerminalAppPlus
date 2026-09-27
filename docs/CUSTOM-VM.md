# 自定义 U-Boot 虚拟机

当前入口：**设置 → 实验室 → 自定义 U-Boot 启动镜像**。
也可以从 **设置 → 虚拟机** 添加镜像、切换已有虚拟机。

1. 在系统文件选择器中选择已解压的 **ARM64 raw 整盘镜像**。
2. 输入名称。若本机尚无可复用的 U-Boot，选择适用于 **crosvm ARM64** 的 U-Boot 文件。
3. 点击导入。App 将系统磁盘和 U-Boot 复制到独立私有目录；源文件和原 Debian 不变。
4. 导入完成后选择是否切换。运行中的 VM 应先在 Guest 内正常关机；界面也提供明确的“强制停止并切换”，这会丢失未保存的工作。
5. 自定义 VM 启动后打开串口控制台，使用 Guest 自己的登录方式，不自动输入任何命令或账户。

当前第一版默认 2 GiB 内存、匹配宿主 CPU、非保护 VM、2D GPU 后端（已启用的可用 gfxstream 设置仍可覆盖它）。
内核由 U-Boot 从磁盘加载。镜像需要兼容 ARM64 AVF/crosvm 设备和相应引导方式；通过格式检查不代表一定能引导。

不支持直接导入 qcow2、压缩包、ISO 安装盘或只有文件系统内容的 rootfs 文件。
空间不足或取消导入会清理未完成副本；零数据区域以稀疏方式写入。

## 切回默认 Debian

在“设置 → 虚拟机”选择“默认 Debian”。这只是换回原磁盘与启动配置，不是恢复出厂。
原路径 `files/linux` 保持不变；自定义系统存放在 `files/virtual-machines/<UUID>/`。
每台自定义 VM 拥有独立的 `system.raw`、`u-boot.bin` 和 `profile.json`。

当前尚未开放资源编辑、任意 `vm_config.json` 编辑、直接内核导入和首次设置向导。
这些属于下一批功能。当前 VM 为自定义系统时，原 Debian 专用恢复操作隐藏，避免误重置原系统。

## ttyd 与图形服务

自定义系统不自动安装官方 Guest agent、ttyd 或采集服务；没有这些服务不会停止 VM。
终端页连接失败时可以打开控制台，图形页通过同一个 VM 的采集服务获取画面。
图形服务的代码、安装命令和前提条件见 [Guest 图形采集服务](GUEST-SCREEN-SERVICE.md)。

## U-Boot 来源

此前验证过的版本、SHA256 和 AOSP 来源记录在 [直接控制台实验](experiments/DIRECT-CONSOLE.md)。
App 当前不捆绑该二进制；可复用既有实验路径 `files/console-probe/u-boot.bin`，或从文件选择器自行导入。
