# 终端 Plus / Terminal Plus

基于本地 Android 17 AOSP TerminalApp 的独立 Android Studio 工程。
应用包名：`com.android.virtualization.terminal.plus`；源码 namespace 保持 AOSP 原名。
无需 AOSP/Soong、平台签名或宿主 root，以普通 APK 安装，与原版终端并存。

## 构建

Android Studio 打开本目录，使用其内置 JDK 21，同步 Gradle 后运行 `app`。
环境要求：Android SDK Platform 37、Build Tools 36.0.0，网络可访问 Google Maven/Maven Central。
本工程使用 AGP 9.4.1、Gradle 9.8.0；compile/min/target SDK 均为 37。

```sh
./tools/build.sh
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`tools/build.sh` 在 macOS 自动使用 `/Applications/Android Studio.app` 的 JBR。
其他环境设置 JAVA_HOME，并通过 Android Studio 或 local.properties 指定 SDK。
只需根目录工程；`refs/` 与 `artifacts/` 均不参与构建。
编译用隐藏 API JAR 是 compileOnly，**不会打入 APK**，来源和哈希见 app/libs/README.md。
运行时使用设备自身 AVF 实现，因此 API 37 也不保证所有厂商 AVF 实现完全兼容。

屏幕服务的代码位置、自动配置机制及一键安装/修复命令见
[虚拟机屏幕采集服务说明](docs/GUEST-SCREEN-SERVICE.md)。
自定义 U-Boot / Linux 镜像的 ttyd 安装、认证方式与开机启动配置见
[自定义镜像接入 ttyd](docs/CUSTOM-TTYD.md)。

## 自动构建与发布

推送 `main` 自动构建检查；推送 `v17.0.1` 这样的版本标签后，GitHub Actions 自动签名并向 Release 上传 `app-release.apk`。Guest 工具已内置，构建报告和对应源码归档保存在 Actions artifacts。debug 与发布版签名不同，首次切换前请先备份 VM 数据。触发方式、签名与产物说明见 [发布指南](docs/RELEASING.md)。

## 安装与授权

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.android.virtualization.terminal.plus android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant com.android.virtualization.terminal.plus android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

也可运行 `tools/grant-permissions.sh`。首次启动保留可复制授权命令和重新检查，
并提供「使用 Shizuku 配置」：先启动 Shizuku，允许终端 Plus 使用 Shizuku 后，
自动授予本应用的两个 AVF 权限，检查成功后进入终端。无需电脑输入命令。
该功能不改变 SELinux 策略，也不解锁原生显示服务。
随后按原版流程授予通知、本地网络和音频权限，下载约 628 MB Debian 镜像。
默认 4 GB 内存（可在「设置 → 虚拟机 → 配置」调整），CPU 使用原版 match_host 拓扑。
没有使用 `su`、平台证书、sharedUserId 或系统分区安装。
虚拟机里的 `sudo` 是 Debian 自身权限，与 Android 宿主 root 无关。

## 已验证

目标：PKB110 / MT6991 / Android 17 / GenieZone。

- Gradle 独立构建 APK，普通 `/data/app` 安装，包标志没有 SYSTEM/PRIVILEGED。
- 两个 `pm grant` 成功，应用权限检查通过。
- 原版 Debian 13 启动、guest agent 注册、ttyd 终端输入输出。
- `nproc` = 8；4 GB 配置下 `free -m` 总内存约 3907 MiB。
- Debian 联网，`apt update` 成功。
- 原版端口控制界面发现 Python HTTP 服务；启用 18080 后电脑经 ADB 转发
  访问手机 localhost，返回 `terminal-plus-port-forward-ok`。测试后已关闭。
- Guest proxy 的分片头、220 KB 数据、TCP 半关闭及非法端口测试通过。

该设备原始 5100000 内核停在 dma_atomic_pool_init，需要精确校验哈希的
**虚拟机内核副本兼容修正**；原 vmlinuz 保留。实现及限制见
[docs/GENIEZONE.md](docs/GENIEZONE.md)。不对未知内核盲目打补丁。

## 与系统版的差异与限制

- 保留 AOSP 新版 Compose 界面、终端 WebView、多标签、设置、镜像安装与恢复流程。
- 普通应用无法直接创建 vsock；终端和端口转发使用 AVF.connectVsock。
- cidata 增加一个以 droid 身份运行的 guest localhost 代理，替代需要宿主直接
  监听 vsock 的原版 JNI 转发。仅监听 vsock，校验连接来自宿主 CID 2；手机端仅绑定回环地址。
- **这台 ROM 的原生 VM 显示 Binder 服务被 SELinux 限制给系统应用。两个 AVF 开发权限
  无法解锁。本 AOSP 显示实验分支恢复原版 Compose 显示入口和分辨率设置，
  以 KMS/vsock 画面采集替代受限 Binder；App 会为预构建 Debian 自动配置并启用采集服务。**
  GPU 后端、虚拟显示与输入设备、渲染器设置和 guest 资源保持原样；这不代表硬件 3D 加速已验证。
- 下载目录数字并不是 Android 或 Debian 版本，且 `latest` 不一定最新。
  详见 [docs/IMAGE-VERSIONS.md](docs/IMAGE-VERSIONS.md)。
- 当前交付 APK 使用本机 debug 签名，适合自用测试；不是公开发布签名。

## 检查与 guest 资源再生成

```sh
./tools/build.sh :app:lintDebug
python3 -m unittest discover -s tests -v
python3 -m unittest discover -s tools/tests -v
# 只有修改 guest 文件时才需要：
python3 -m venv .venv
.venv/bin/pip install pycdlib
# Android 官方镜像的 cidata 与自动部署服务：
.venv/bin/python tools/build-cidata.py
# 自定义镜像的 PLUS_TOOLS 安装盘：
.venv/bin/python tools/build-guest-tools-iso.py
.venv/bin/python tools/verify-guest-tools-iso.py
# 仅修改 NoCloud 模板/网络配置时：
.venv/bin/python tools/build-cloud-init-template.py
```

正常 Android Studio 构建直接使用已生成 cidata，无需 Python 或 ISO 工具。
升级 guest 服务时已有虚拟机应从新 cidata 的 init.sh 应用更新；不要删除用户 root_part。
内存、端口与终端数据位于独立应用沙箱；卸载 Plus 会删除其虚拟机数据。

AOSP 来源、参考项目及移植边界见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。
保留原始 Apache 2.0 版权声明和 NOTICE。

原版基线与分步移植提交说明见 [docs/HISTORY.md](docs/HISTORY.md)。
当前工作区相对原版的改动及精简建议见 [源码核对](docs/SOURCE-AUDIT.md)。

## 终端与图形

主界面提供 ttyd、串口控制台和虚拟显示器入口，操作同一台 VM。
关闭最后一个 ttyd 标签后保留空白提示页，可点击加号重新打开；不会停止 VM。
串口保留 Guest 原有登录流程，支持快捷键、文本选择和双指缩放，字号自动保存。
直接加载 `/system/fonts/DroidSansMono.ttf`；缺失时回退到系统等宽字体，APK 不内置字体。
串口不再自动同步 Guest TTY 尺寸，需要窗口尺寸协商的 TUI 可使用 ttyd 或 SSH。

图形页面复用 AOSP 显示操作和输入逻辑，通过 Guest KMS 采集传输现有画面。
支持重连、主平面和标准光标合成；使用 LZ4 优先的无损传输及最新帧队列。
默认目标 30 fps，实际帧率受分辨率、Guest 渲染和设备负载影响。
历史实机采样与限制见 [KMS 实验记录](docs/experiments/KMS-CAPTURE.md)。

## 虚拟机管理

“初始设置”支持官方下载、导入官方格式 Debian 镜像包，或导入 IMG/RAW/QCOW2 磁盘。
“设置 → 虚拟机”统一管理切换、重命名、离线克隆、删除及每台 VM 的启动配置。
支持 U-Boot 或直接内核启动，配置页提供默认页面、内存、CPU 拓扑和 JSON 编辑。
具体格式、资源限制与配置恢复见 [虚拟机管理](docs/CUSTOM-VM.md)。

## 文档

- [虚拟机管理与导入](docs/CUSTOM-VM.md)
- [初始配置 cloud-init](docs/CLOUD-INIT.md)：账户、SSH 公钥及首次启动 DHCP。
- [自定义镜像 Guest 工具盘（实验）](docs/GUEST-TOOLS-ISO.md)：通过只读安装盘部署 ttyd 和图形采集服务。
- [自定义镜像接入 ttyd](docs/CUSTOM-TTYD.md)：认证、启动命令和 systemd 配置。
- [qcow2 转换工具](third_party/qemu-img/README.md)：内置 qemu-img 的来源、许可和对应源码；按文件内容识别镜像，转换为 RAW 后导入。
- [Guest 图形采集与端口代理](docs/GUEST-SCREEN-SERVICE.md)：安装、完整 systemd 配置和排查。
- [文档索引](docs/README.md)：当前使用指南、来源、兼容性和历史验证记录。

## Credit

- [Termux](https://github.com/termux/termux-app)：控制台的 `terminal-emulator`
  与 `terminal-view`，提供 ANSI 解析、终端渲染、键盘输入及文本选择。
  固定来源提交为 `8629e632fcb95da272221be327db653fb24befe9`。
- [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：
  Termux 组件中的上游基础代码。
- [Podroid](https://github.com/ExTV/Podroid)：AVF 控制台流连接方案参考；
  本项目直接使用 Termux 官方组件，没有复制 Podroid 的 UI 或桥接实现。

第三方许可及本地调整见 [third_party/termux/README.md](third_party/termux/README.md)。

内置 U-Boot 的来源和许可见 [第三方说明](third_party/u-boot/README.md)。
压缩解码使用 [LZ4 Java](https://github.com/yawkat/lz4-java) 1.12.0（Apache-2.0）。

Guest 工具盘内置 [ttyd](https://github.com/tsl0922/ttyd) 1.7.7 ARM64 静态程序，来源及许可见 [third_party/ttyd/NOTICE](third_party/ttyd/NOTICE)。
