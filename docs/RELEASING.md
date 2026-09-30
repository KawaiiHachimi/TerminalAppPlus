# 自动构建与发布

工作流：[Android build and release](../.github/workflows/android.yml)。

## 触发方式

- 推送 `main` 或提交面向 `main` 的 PR：构建 debug、unsigned release，执行 JVM/Python 测试、lint 和 APK 资源检查；产物保存为 Actions artifacts，保留 14 天。
- 推送 `v主版本.次版本.修订号` 标签：检查通过后签名 release APK，发布 GitHub Release。
- 标签带 `-beta.1` 等后缀：发布为预发布版本。
- Actions 页面可手动运行；选择 main 只构建，选择版本标签会执行发布。重跑同一标签会更新同名附件。

例如，在要发布的 main 提交上执行：

```sh
git pull --ff-only
git tag v17.0.1
git push origin v17.0.1
```

使用尚未存在的新标签，不要移动已经发布的标签。默认版本名仍为 `17.0-plus.1`；CI 发布版本名来自标签去掉 `v`，versionCode 为 `1000 + github.run_number`，避免后续发布覆盖安装时版本号不递增。同一次 run 的重试保持原 versionCode。

Release 仅上传 `app-release.apk`：使用专用发布密钥签名的非 debuggable APK。不额外上传 `.idsig`、Guest 安装包或校验文件；Guest 工具已随 APK 提供。

GitHub 自动显示的标签源码 zip/tar.gz 属于平台提供的链接。构建报告保留在 Actions artifacts；qemu-img 对应源码归档单独保存为 `qemu-img-corresponding-sources` artifact（90 天），固定上游来源、哈希与归档脚本保留在仓库。当前未改变的 qemu-img 二进制还可使用 [beta.3 保留的源码归档](https://github.com/KawaiiHachimi/TerminalAppPlus/releases/download/v17.0.1-beta.3/qemu-img-sources.tar.gz)。更换二进制时需同步更新对应源码分发位置。

## 签名与权限

发布使用以下仓库 Actions Secrets，不把密钥放入 Git：

| Secret | 内容 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | PKCS#12 密钥库的 Base64 |
| `ANDROID_RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `ANDROID_RELEASE_KEY_PASSWORD` | 私钥密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | 密钥别名 |

专用密钥已独立生成，后续版本必须继续使用同一密钥。首次从当前 debug 版切换到发布版时，Android 不允许不同签名覆盖安装；先自行备份虚拟机磁盘和配置。不要为换签名直接卸载而丢失 App 私有数据。Actions 的临时 debug APK 只用于 CI 检查，不作为稳定更新渠道。

构建 job 仅有仓库读取权限；签名和 Release 写入发生在标签触发的独立发布 job。工作流不改变仓库可见性，私有仓库的 Release 仍需相应访问权限。

## 维护

工作流使用 Ubuntu 24.04、JDK 21、Android Platform 37.0 和 Build Tools 36.0.0，与独立工程的 AIDL 编译路径一致。第三方 Actions 固定提交 SHA，升级时同时更新 SHA 和注释版本。

发布先创建草稿、上传附件，成功后再公开为普通 Release（仅在仓库权限范围内可见）。失败时检查 Actions 日志，修复后重跑；缺少签名 Secrets 会直接失败，不会改用随机密钥。
