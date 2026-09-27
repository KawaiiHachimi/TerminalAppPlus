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
默认 4 GB 内存（可在「设置 → 高级」调整），CPU 使用原版 match_host 拓扑。
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
# 只有修改 guest 文件时才需要：
python3 -m venv .venv
.venv/bin/pip install pycdlib
.venv/bin/python tools/build-cidata.py
```

正常 Android Studio 构建直接使用已生成 cidata，无需 Python 或 ISO 工具。
升级 guest 服务时已有虚拟机应从新 cidata 的 init.sh 应用更新；不要删除用户 root_part。
内存、端口与终端数据位于独立应用沙箱；卸载 Plus 会删除其虚拟机数据。

AOSP 来源、参考项目及移植边界见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。
保留原始 Apache 2.0 版权声明和 NOTICE。

原版基线与分步移植提交说明见 [docs/HISTORY.md](docs/HISTORY.md)。

## 实验分支：AVF 原生控制台

`codex/feat-direct-console` 在 debug 构建中提供独立的控制台实验入口，
用 AVF 输入输出流连接来宾串口，不依赖 ttyd。主界面设置按钮左侧的控制台按钮
可进入同一台正在运行的 Debian VM 的串口终端；原有 ttyd 终端仍可使用。
两个入口是不同 shell 会话，共用系统、磁盘和进程。关闭串口页面不会停止 VM。
独立 BusyBox 和 U-Boot 镜像启动保留在侧栏实验室中。实验终端直接加载手机上的
`/system/fonts/DroidSansMono.ttf`，避免厂商主题替换 monospace 字体别名；
APK 不打包字体。设备缺少该文件时回退到系统等宽字体。
实验步骤与实机结果见 [DIRECT-CONSOLE.md](docs/experiments/DIRECT-CONSOLE.md)。

### Credit

- [Termux](https://github.com/termux/termux-app)：实验界面的 `terminal-emulator`
  与 `terminal-view`，提供 ANSI 解析、终端渲染、键盘输入及文本选择。
  固定来源提交为 `8629e632fcb95da272221be327db653fb24befe9`。
- [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：
  Termux 组件中的上游基础代码。
- [Podroid](https://github.com/ExTV/Podroid)：AVF 控制台流连接方案参考；
  本分支直接使用 Termux 官方组件，没有复制 Podroid 的 UI 或桥接实现。

第三方许可及本地调整见 [third_party/termux/README.md](third_party/termux/README.md)。

AOSP 显示分支复用原版全屏、缩放/平移、软键盘、触控板、鼠标捕获和剪贴板交互。
图形分辨率按原版设置调整；启动时等待采集服务就绪并自动重连。
新镜像通过 cidata 安装服务，已有预构建 Debian 通过独立的本地 ttyd 会话幂等升级。
该升级只适用于 App 管理的已知 cidata 版本，需要来宾原有的非交互 sudo 权限；
不重置磁盘、不改变账号或密码。自定义镜像仍需自行提供兼容采集服务。
当前采集支持主平面及标准线性 ARGB 硬件光标叠加；通用叠加平面和特殊光标格式仍未覆盖。

显示通道默认目标 30 fps，采用可复用缓冲区、最新帧队列、原始像素 GPU 色彩转换，
以及 LZ4 优先的无损传输（无 liblz4 的来宾回退到 zlib/原始帧）。
实际帧率受分辨率、来宾桌面渲染和设备负载影响；本机采样半分辨率接近 29 fps，
全分辨率约 18–20 fps，尚不代表稳定 30/60 fps。
压缩解码使用 [维护中的 LZ4 Java](https://github.com/yawkat/lz4-java) 1.12.0（Apache-2.0）。

控制台双指缩放后的字号会自动保存，按 SP 还原，重新进入或重启 App 后继续使用。
控制台不再带侧栏；三个实验入口统一放在「设置 → 实验室」（位于「恢复」上方）。
实验室目前仅在 debug 构建显示。

### 自定义虚拟机

可在“设置 → 虚拟机”切换默认 Debian 与独立的 U-Boot 镜像；实验室仅保留镜像导入入口。格式限制、U-Boot 选择和切换说明见 [自定义 U-Boot 虚拟机](docs/CUSTOM-VM.md)。

控制台现在显示实际列数、行数及 Guest 同步状态；缩放字体、旋转和软键盘变化通过统一的 `terminal-plus-guest.service` 同步到串口 TTY。点击尺寸状态可复制安装命令。该服务同时管理图形采集和端口代理，详见 [Guest 服务说明](docs/GUEST-SCREEN-SERVICE.md)。尺寸通知路线参考 Podroid，协议与服务独立实现。
