# dev：Android 17 VirGL2、H.264 和 safe-JIT 实验

本分支为三个独立的可选能力；默认仍使用原有 GPU 配置和无损 KMS 传输。
本地构建与协议验证不代表手机上的硬件加速已通过。测试前先保存当前 VM 配置。

## Android 17 是否支持 VirGL2

2026-11-01 核对 AOSP `android17-release`：

- [AVF GPU 参数转换](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/android17-release/android/virtmgr/src/crosvm.rs) 可传递 backend、context types、EGL/GLES 等字段。
- [Rutabaga Android.bp](https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/refs/heads/android17-release/Android.bp) 的 `librutabaga_gfx` features 只包含 `gfxstream`，没有 `virgl_renderer`。
- [Rutabaga 初始化](https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/refs/heads/android17-release/src/rutabaga_core.rs) 在未编译 `virgl_renderer` 时拒绝该后端，返回 `InvalidRutabagaBuild`。

结论：配置接口仍保留该路线；标准 AOSP Android 17 构建未启用这个后端，厂商可以另行启用。
U-AVF 在三星 ROM 上的 VirGL2 结果不意味着 OPPO 的系统 crosvm 也包含它。
此前 PKB110 的 `invalid rutabaga build parameters` 与此限制一致。
App 的两个 AVF 权限不能添加系统二进制未编译的后端。本分支不替换系统 crosvm。

VirGL2 实机重试仍失败，因此已移除本轮新增的 `gpu_profile` 预设入口；通用 `gpu` 配置保持原有行为。

## 可选 H.264 传输

在“设置 → 虚拟机 → 配置 → 资源设置 → 显示传输”选择“无损（默认）”或“H.264”。
该选项与 JSON 编辑共用同一份配置；也可以在配置顶层手动增加：

```json
"display_transport": "h264"
```

停止后重新启动 VM 生效。恢复 `"display_transport": "raw"` 或删除该字段即可恢复原有无损路径。
这里 raw 指现有 BGRX/LZ4/zlib 路径，不意味着关闭其压缩。

先更新 Guest Tools（新 ISO / 新 bundle），仍由同一个 `terminal-plus-guest.service` 管理。
无需增加服务。自定义 VM 使用新工具盘中的 `install.sh --force`；若旧工具盘仍挂载，先卸载再重新挂载。
官方 Debian 沿用既有工具更新流程。
H.264 依赖 Guest 发行版提供的 ffmpeg 和 libx264；不内置二进制，也不在普通安装时强制安装额外编码依赖。

```sh
# Debian/Ubuntu
sudo apt update
sudo apt install ffmpeg
# 其他发行版从其可用仓库安装提供 libx264 encoder 的 ffmpeg；RPM 的精简 ffmpeg 未必提供。
ffmpeg -hide_banner -encoders | grep libx264
sudo systemctl restart terminal-plus-guest.service
```

路径为现有 DRM/KMS CPU 读回和光标合成 → ffmpeg/libx264 软件编码 → vsock 7683 → Android 硬件 AVC 解码 → Surface。
只接受 CID 2；新增 `H` 请求，响应 `PAV1` + 大端宽高，然后重复“大端 AU 长度 + Annex-B 字节”。
AU 最大 4 MiB，图像最大 32 MiB；最大每边 4096。App 自动请求偶数尺寸；若 Guest 仍输出奇数尺寸，编码端补齐一行/列以满足 YUV420 要求。
SPS/PPS 在首个 AU，x264 显式输出 AUD、无 B 帧并周期性重复头部。
布局尺寸、全屏或分辨率比例变化会主动重建编码连接，并使用布局尺寸作为呈现目标。Guest 修改尺寸会结束当前编码会话并重连，重新建立解码器。旧 Guest 不认识 H 时明确失败，用户可恢复 raw。

传输沿用 KMS，因此兼容原采集器支持的桌面或 TTY；不依赖 X11、第二个桌面会话或 root Android。
原采集器不支持的非线性/modifier framebuffer 不会因编码而变为可读；这可能限制真正加速后的桌面。
输入继续走 AOSP/AVF，编码服务不接收任意命令或注入输入。
使用 4 个软件编码线程会增加 Guest CPU 和功耗；H.264 有损，不保证帧率或低于 raw 的端到端延迟。
解码器只选择硬件实现，没有合适实现时显示错误，不静默使用软件解码。
停止/后台/Surface 销毁关闭 vsock，取消读取，释放解码器；Guest 编码器在客户端断开、尺寸变化或超时后回收。

## safe-JIT

Guest Tools 同时安装 `/usr/local/bin/terminal-plus-safe-jit.py`。默认按进程运行，不修改系统配置：

```sh
terminal-plus-safe-jit.py --probe
terminal-plus-safe-jit.py glxinfo -B
terminal-plus-safe-jit.py -- your-program your-arguments
```

仅在 ARM64、AT_HWCAP 没有 SVE、可检测到 LLVM 19–24 将 CPU 识别为 cortex-x925 时启用。
其他情况原样启动。`--probe` 只检测，不编译或安装；首次显式运行目标程序时，在用户私有缓存目录
编译一个小型 Linux 共享库，需要 Guest 的 `cc`/`gcc` 和 libc 开发头文件，例如：

```sh
sudo apt install gcc libc6-dev
terminal-plus-safe-jit.py weston-simple-egl
```

实机 Debian 的 LLVM 19.1.7 并不读取 `LLVM_CPUINFO`，因此只提供简化 cpuinfo 无法修复。
当前 wrapper 为目标进程设置 LD_PRELOAD；在检测命中的 Guest 中，兼容库对无 SVE 环境统一返回 generic，避免大小核迁移时再次选中 SVE CPU 目标；
不改变内核公布的特性、不修改 LLVM/Mesa 二进制或全局环境。保留原有 LD_PRELOAD，
使用独立版本化 shader cache，保留旧缓存。LLVM C++ StringRef 返回 ABI 的适配只面向当前 Linux ARM64；
LLVM 自身内部直接调用的 CPU 查询可能仍显示 X925，应核对真正的 Mesa 调用绑定和负载。
缺少编译器则明确报错，不宣称已修复。编译后的库只在 Guest 用户缓存中生成，不打包 Android 二进制。

这是 JIT workaround，不能修复已编译程序中的 SVE 指令，也不能添加宿主未编译的 GPU 后端。
Snap/Flatpak、自带 LLVM、静态链接或有不同符号可见性的程序需分别验证；沙箱也可能无法读取
用户缓存的 preload/fixture，不宣称已经自动适配。

### Guest 全局模式（可选）

对于使用 glibc 的 ARM64 Linux Guest，可以一次启用全局兼容，覆盖使用相应动态加载器的新进程，
无需按 GNOME、设置、文件管理器等进程名称逐一配置。默认安装 Guest Tools 不会自动启用。

先更新 Guest Tools。编译器只在首次生成或更新兼容库时使用：

```sh
# Debian/Ubuntu
sudo apt install gcc libc6-dev
# Fedora/RHEL 系：sudo dnf install gcc glibc-devel
sudo terminal-plus-safe-jit.py --install-global
terminal-plus-safe-jit.py --status
```

安装器要求 root、Linux/glibc，以及检测到的 ARM64 / 无 SVE / LLVM X925 组合。
在 root 所有且其他用户不可写的目录编译兼容库，先在独立子进程中验证，再将其路径追加到
`/etc/ld.so.preload`。保留已有条目和注释，重复执行不重复添加，不增加常驻服务。
检测会遍历允许的 vCPU，避免只在小核上运行时漏判。库在无 SVE 时对所有核心统一使用 generic，SVE 可用时转发原查询。新进程生效，完整刷新桌面需注销后重新登录或重启 Guest。

撤销：

```sh
sudo terminal-plus-safe-jit.py --remove-global
```

只移除 Plus 管理的路径行，保留库文件供仍在运行的进程使用；旧进程要退出并重新启动才会解除加载。
`--status` 的 `enabled` 表示已配置，`loaded` 表示当前命令进程实际加载。`--probe` 绕过本库的 CPU 名称修正，仍检测原始环境。

目前全局模式只支持 glibc；musl/Alpine 不自动配置。静态链接、不可拦截的 LLVM 调用、
部分沙箱或已经编译进程序的 SVE 指令不保证覆盖。它不会向 Guest 添加 SVE 支持。
当前 Ubuntu 26.10 / LLVM 22 已进行实机验证；其他发行版需要分别验证，不能仅凭名称认定兼容。

## 验证范围与来源

参考 [U-AVF 4e466f6](https://github.com/dt0-imsyu/U-AVF/tree/4e466f6dc7341d49364783ba9fe0672d26c1cbb5) 的 VirGL2 参数、
编码传输和 safe-JIT 思路；该仓库的 LICENSE 将未列入限制范围的第一方文件保留为 Apache-2.0。
本分支使用独立实现的 PAV1/KMS 编码协议与通用命令 wrapper，未复制其受限 UI/workspace 文件或捆绑平台镜像。

本地测试覆盖参数校验、SPS/PPS 与长度边界、分包 AUD、safe-JIT 条件和缓存隔离。
设置 `PLUS_FFMPEG=/absolute/path/to/ffmpeg` 运行 `python3 -m unittest discover -s tests -v` 可额外验证真实编码、
解码像素颜色、客户端断开后的进程回收。没有该变量时此项集成测试明确跳过。
实机验证记录见本文后续小节；未通过或未测试的项目不据此宣称完成。

## 2026-11-01 PKB110 实机验证

通过 USB ADB 安装同签名 `17.0.4-dev`（code 1028）。手机当时没有 Plus 安装，因此创建了新的默认 Debian；
系统自带终端未操作。Guest 为 Linux 6.12.93 / Debian，安装发行版 ffmpeg 7.1.5、Mesa 工具和用于首次编译 wrapper 的 gcc/libc6-dev。

- VirGL2 预设确实进入 crosvm 参数：`backend=virglrenderer,context-types=virgl2,egl=true,gles=true,surfaceless=true`。
  GPU worker 仍报 `invalid rutabaga build parameters` 并退出。已恢复原有 GPU 配置；没有宣称实际 3D 加速通过。
- H.264：Weston 活动 KMS 输出，经 libx264 编码到 Android `c2.mtk.avc.decoder`，实际绘制 Surface。
  `weston-simple-shm` 动态彩色窗口的两次截图内容不同，验证持续更新而非只有首帧；当前测试流为 628×1194。
- 前后台测试发现原先从 Context 获取 lifecycle 会漏掉主题包装场景；改用已附着视图的 ViewTreeLifecycleOwner。
  修复后后台 `pgrep ffmpeg` 无进程，回到页面重新建立硬件解码器。未测量持续帧率、延迟或功耗，不保证任意尺寸下更快。
- safe-JIT 检测到无 SVE / X925。单用 LLVM_CPUINFO 无效，因此改为按进程 preload。
  动态链接跟踪确认 `libvulkan_lvp.so` 的 `_ZN4llvm3sys14getHostCPUNameEv`（LLVM_19.1）绑定到用户缓存兼容库，
  限时 Weston EGL 绘制到达超时退出，没有因 SVE SIGILL 退出。这是软件渲染兼容验证，不是 GPU 硬件加速。

横竖屏、各种桌面/沙箱、不同分辨率及长时间帧率仍需后续验证。
配置恢复时保留默认 GPU，显示传输设置为 h264，便于继续体验；改回 raw 可恢复无损路径。
原始测试日志与截图位于忽略的 `artifacts/dev-gpu-research/`，不提交带设备会话信息的原始抓取。

## 分辨率与桌面应用复测

已补上布局尺寸监听，H.264 呈现目标跟随窗口布局；尺寸改变主动重建连接，奇数尺寸输入补齐编码边界。
Activity 旋转后不再重置到配置的默认启动页。移除了本轮新增的 VirGL2 预设字段与代码，仅保留通用 gpu 配置。

实机确认：半分辨率普通窗口 628×1194、全屏 628×1380、横屏全屏 1380×628 均能切换；
全分辨率普通窗口布局 1256×2389 对应请求/解码 1256×2388（H.264 偶数对齐）。当前设置保留为“全”。
打开分辨率弹窗曾触发 ANR：即时堆栈显示主线程在 IDebianService.readClipboard 的同步 Binder RPC 上等待。
剪贴板同步现改为后台 single-flight，不在 GuestAgentController 生命周期锁内等待；阻塞请求不再堆积焦点回调。
已重装并复测弹窗打开、选中、确认与返回。

Firefox 使用临时独立 profile 显示本地动画/时钟页面，横屏和旋转回竖屏均有截图；
glxgears 显示转动齿轮；glmark2-wayland 的 640×480、build:duration=5 短测试退出 0。
后两者实际渲染器为 Zink + llvmpipe (LLVM 19.1.7)，属于软件渲染。
短测试的 glmark2 score 260 不代表完整套件成绩，应用绘制 FPS 也不代表手机实际显示帧率。
Android Use 配置已核验为单个 android-use-by-andromeld 实例，phone_connect 已连接；未添加重复项。

## Debian sid GNOME 复测及已知限制

Debian sid generic ARM64 daily（20261101-2649）配置 8 GiB 内存、16 GiB 磁盘，
cloud-init 账户初始化、首次 DHCP 与根文件系统扩容正常；安装 GDM 和最小 GNOME 51 后可登录 Wayland 会话。
修复了合成按键 scanCode 为 0 时未转发，以及进入显示页、触摸时 Surface 未获取焦点的问题。

反复调整尺寸、切换 ttyd 后返回图形页，仍出现过只有壁纸的情况，不能视为完整通过。
当时检测到两个活动 DRM CRTC，Mutter 的活动输出包含 Virtual-2；尚需核对显示热插拔与采集输出选择，原因未最终确定。
GNOME 空闲关闭输出时也可能出现 No active DRM scanout；该状态不等于 Guest 服务未安装。
H.264 保持可选，默认无损路径不变；尚未完成同负载下的帧率、端到端延迟和功耗对比。
