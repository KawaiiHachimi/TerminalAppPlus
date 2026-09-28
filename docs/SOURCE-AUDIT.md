# 相对 AOSP 原版的改动与精简建议

核对日期：2026-09-28。基线为 `ed3c133`（`aosp-17-baseline`），来自 AOSP Virtualization `22f1c9ee92b146e10c8f5e71338618f77ab1631e` 的 TerminalApp。对比对象为当前工作区，包含尚未提交的虚拟机管理改动，不能仅用 HEAD 代表当前源码。原版 `java/`、`res/` 等已迁至 `app/src/main/`，普通 diff 的增删行数会混入目录迁移和第三方代码，不代表全部为新写功能。

## 已改变的部分

| 范围 | 相对原版的变化 |
| --- | --- |
| 构建 | 从 Soong 改为独立 Gradle/Android Studio 工程；补齐 AIDL、protobuf/gRPC 和编译期隐藏 API 签名；运行时仍使用手机 AVF。 |
| 应用身份与授权 | Plus 包名、名称、蓝色图标；普通 APK；两个 AVF 开发权限可通过 ADB 或 Shizuku 授予；隐藏 API 访问适配。 |
| VM 启动兼容 | 修正启动并发、日志读取和连接过程；对已知 MT6991/GenieZone 内核副本做精确哈希约束的兼容修正，未知内核不套用。 |
| 终端与网络 | ttyd 通过 AVF vsock 桥接；Guest localhost 代理替换普通应用不可用的宿主监听/JNI 路线；保留 AOSP 端口控制。 |
| 串口控制台 | 加入 Termux emulator/view，连接同一台 VM 的串口；快捷键、触摸输入、缩放和字号记忆；直接读系统等宽字体文件。独立于 ttyd，会话不同但共享磁盘与系统。 |
| 图形 | 新版显示页改用 Guest DRM/KMS → vsock → App 渲染；保留 AOSP 显示操作与输入转发；加入光标合成、坐标修正、重连、最新帧队列、LZ4/原始像素传输与 GPU 色彩转换。不能据此认定来宾硬件 3D 加速可用。 |
| 生命周期 | VM 状态与 ttyd 可用状态分离；ttyd 不存在、页面关闭或连接失败不直接结束 VM。 |
| Guest 集成 | 单个 `terminal-plus-guest.service` 监管图形采集和端口代理；cidata 首次安装，已知官方 Debian 可通过认证 ttyd 通道更新，自定义镜像手动安装。 |
| 虚拟机管理 | 首次选择官方下载/自定义磁盘；U-Boot 或内核/initrd 启动；预置 U-Boot；独立配置及磁盘、切换、重命名、稀疏克隆、删除；每台 VM 的 JSON、内存、CPU 拓扑、默认页面与配置恢复。CPU 当前为单核/匹配宿主，不是任意核心数。 |
| 工程维护 | 新增设备兼容、Guest 服务、自定义镜像、实验记录、来源与许可说明，以及传输、图形、配置、资源打包等检查。 |

## 本次移除

- Guest 串口尺寸同步：删除 App vsock 7684 客户端、Termux 会话通知接口、控制台同步状态与弹窗、Guest resize 模块和专属测试。保留本地 emulator resize，否则字体缩放后本地显示也会坏。
- 重新生成 cidata 和安装包；统一服务仅启动 capture/proxy。升级安装器保留旧 resize 单元的停用及脚本清理，这是迁移逻辑，不是功能残留。已安装在 Guest 的旧服务需更新后才会退出旧模块。
- 删除“高级 → 内存、CPU 与启动配置”重复跳转。“设置 → 虚拟机 → 配置”是唯一资源配置入口；高级保留显示分辨率与保持唤醒。
- 去掉控制台恒为 true 的实验分支标志及相关无用引用；修正统一 Guest 服务后仍指向旧 service 文件的测试。

## 值得继续优化的内容

### 可以优先清理：调用关系已经明确

1. `new2/ui/SettingsScreen.kt` 的 `MemorySizeDialog` 及其格式化函数没有调用者；`SettingsViewModel` 仍维护旧全局内存、重启弹窗状态与 setter。新设置已经按 VM 保存 JSON，可移除旧 UI 状态。但 `PREFS_NAME`、`KEY_MEMORY_MIB`、默认值仍用于 `VmProfiles.readConfig` 迁移已有设置，应保留迁移读取。
2. `VmController` 的旧 `graphicsAccelerationType`、支持查询、setter 与私有 `setGpuConfig` 无调用者；实际 GPU 来自 `ConfigJson`。可删除这些旧包装，不能删除 JSON 的 GPU 转换或输入/显示设备配置。
3. 已删除实验室页面，但 `plus_laboratory`、`plus_lab_*` 翻译仍存在；`ConsoleProbeActivity`、`ProbeTerminalClient` 的名字仍是原型时期命名。前者可清理，后者可统一改名，收益主要是可读性。

### 收益较大，但需要沿调用链迁移和回归

4. AOSP 原本就带有新旧两套 UI。本工程启动器固定进入 `new2.ui.MainActivity`，仍保留旧 `MainActivity`、`VmLauncherService`、旧设置 Activity、原生 Binder `DisplayProvider` 等。它们不是本项目新增的第二套产品需求。适合单独删除旧启动链及 Manifest 注册，再逐项清理依赖；共享安装器、升级/恢复、端口和显示输入类不能按目录整批删掉。
5. Guest 采集源码同时出现在 `tools/experiments`、Guest 根目录、独立 assets、压缩 bundle 和 cidata。已有生成器减少手工漂移，但仍有仓库/打包重复。建议选一个正式源码目录，构建自动生成分发物；原版 cidata、首次安装的 cidata 和更新 bundle 各有用途，不宜只保留一个而破坏离线安装/升级。
6. `VmProfiles` 集中了持久化、导入/克隆、配置迁移及默认固件；`VmManagementPage` 集中了多个表单和弹窗。可按磁盘操作、配置存储、表单拆分，保持一份配置数据来源，避免再增加另一套实验室入口或 CPU/内存全局设置。
7. JSON 是高级入口，应继续明确支持字段、只读文件与可写磁盘路径约束、保存/恢复语义。当前 last-good 是 AVF 成功启动后的配置，不能宣称验证过 Guest 引导成功。自定义镜像启动成功也不保证 ttyd、Guest agent、剪贴板或桌面服务存在。

### 不建议为了精简直接砍掉

- ttyd 与串口：前者保留原版交互及窗口尺寸协商，后者覆盖引导、恢复和没有 ttyd 的自定义镜像。
- U-Boot 与直接内核启动：面向不同镜像布局，保留一个导入流程中的两种模式即可。
- Guest 采集和代理：当前普通应用路径实际依赖；停止尺寸同步不会让这两项也变得多余。
- GPU 后端和 AOSP 输入逻辑：与被替换的系统显示 Binder 入口不是同一回事。
- 已有镜像迁移、旧服务停用、权限检查及狭窄内核兼容逻辑：它们有明确兼容目的，应在不再支持相应版本/设备后再删。

## 建议顺序

先删除上述明确无调用者的旧设置代码；其次完整退休旧 UI/Service 链；最后收敛 Guest 资源生成与拆分 VM 管理模块。避免把运行路径重构、功能删除和构建资源迁移混成一个大提交，便于逐项验证。
