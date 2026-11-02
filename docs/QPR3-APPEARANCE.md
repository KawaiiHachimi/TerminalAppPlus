# QPR3 外观移植（开发分支）

本轮基于 Pixel QPR3 提取 APK 的行为、资源和现有 AOSP Kotlin 工程恢复外观功能，未加入反编译的 Compose/R8 Java。
提取 APK SHA-256：`a2ed91f6ae903e9fef3f8cc550e9baca4b5dc8fdcaa213558f5396f94867c033`，cidata build ID `16583637`。

“设置 → 外观”提供：

- App 跟随系统、浅色和深色模式；沿用 Material 3 动态配色。
- 终端浅色/深色主题分别选择，或关闭自动切换后固定主题。
- Standard Dark、Standard Light、Dracula、Solarized Dark 四个 QPR3 预设。
- 导入、预览和删除自定义 Alacritty TOML 颜色主题。支持 primary、normal/bright 16 色、cursor、selection 颜色子集，需要 foreground/background；不是完整 Alacritty 配置解析器。
- 导入 TTF/OTF 字体（15 MB 上限）；默认读取设备 `/system/fonts/DroidSansMono.ttf`，不可用时回退等宽字体，不将默认字体打入 APK。
- ttyd 字号设置及缩放保存。原生控制台继续保留自己的双指缩放字号记录。

颜色与字体应用于 ttyd 和 Termux 原生控制台；原生控制台应用前景、背景、光标与 16 色，选择样式沿用 Termux 实现。已打开的 ttyd 页面随设置更新，原生控制台返回页面时更新。
主题以稳定 ID 存储，语言切换不影响选择；导入文件复制到 App 私有目录，不依赖长期访问原 URI。

主题预设、外观文案与新版图标来自 AOSP TerminalApp 的上述提取版本。Kotlin 数据模型、设置页和适配层在 Plus 中重新实现。
图标保留原 PNG 尺寸、透明度和白色符号，只将绿色色相映射到蓝色，保留已有青蓝色渐变；单色图标沿用原路径。
可通过 `tools/recolor-terminal-icon.py` 与原 APK 解码后的 res 目录复现 PNG 换色（开发机需 Pillow）。

本轮不替换 cidata、VM 后端、镜像管理或 Guest Tools，不加入 Wayland/SFTP 等 QPR3 后端能力。
