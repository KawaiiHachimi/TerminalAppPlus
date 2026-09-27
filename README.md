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

## 安装与授权

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.android.virtualization.terminal.plus android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant com.android.virtualization.terminal.plus android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

也可运行 `tools/grant-permissions.sh`。首次启动有可复制授权命令。
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
  无法解锁。已隐藏原生显示入口及其分辨率设置，并移除原生显示 Activity 注册。**
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
