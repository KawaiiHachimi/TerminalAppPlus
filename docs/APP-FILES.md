# 使用文件管理器访问应用数据

终端 Plus 内置 Android Storage Access Framework（SAF）文件提供器。无需 Root 或修改 APK，可在 MT 管理器及其他支持 SAF 的文件管理器中管理本应用的数据。

## 添加存储

1. 安装并打开一次终端 Plus。
2. 在 MT 管理器侧栏选择“添加本地存储”。
3. 在系统文件选择器侧栏找到终端 Plus，选择顶层入口并授权，也可只授权其中一个子目录。

目录结构与 MT 的文件提供器一致：只有一个“终端 Plus”入口，下列目录作为其子目录。顶层用于展示这些目录，不能在顶层新建、删除或重命名目录。

| 子目录 | 内容 |
| --- | --- |
| `data` | 应用内部数据，包括 `files`、`shared_prefs` 等 |
| `user_de_data` | 设备加密存储中的应用数据 |
| `android_data` | 本应用的外部数据目录 |
| `android_obb` | 本应用的 OBB 目录 |

目录可用时才显示，不开放其他应用的数据。

虚拟机文件通常位于 `data/files/virtual-machines`，默认镜像还可能使用 `data/files/linux`。这是 Android 端存储的磁盘镜像与配置，不是 Guest 挂载后的根目录。

支持浏览、新建、读写、重命名和删除。复制文件可由文件管理器通过读写完成。修改磁盘镜像或配置前先停止对应虚拟机，避免与运行中的虚拟机同时写入。

授权会允许所选文件管理器访问整个所选目录，其中可能包含应用配置和认证数据。提供器受系统 `MANAGE_DOCUMENTS` 权限保护；普通应用必须先获得系统文件选择器授予的 URI 权限。

## 实现范围

使用 Android 标准 `DocumentsProvider`，Debug 和 Release 均包含此功能。没有新增第三方依赖，也没有集成 MT 的 APK 注入逻辑。

不实现 MT 私有的 chmod、修改时间、创建软链接等扩展。软链接及 socket 等特殊文件不开放；不允许删除或重命名导出的根目录。

参考：

- [MT 注入文件提供器说明](https://mt.cc/guide/reverse/inject-documents-provider.html)
- [MTDataFilesProvider 公开实现](https://github.com/L-JINBIN/MTDataFilesProvider)：调研参考，未复制代码。
- [Android DocumentsProvider 指南](https://developer.android.com/guide/topics/providers/create-document-provider)
