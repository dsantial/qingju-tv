# 派生来源与第三方声明

## 1. 派生来源：N3urda/hongguoTV（GPL-3.0）

本仓库是 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 的 fork，**自 2026-10-06 起在上游基础上修改**，
按 GPL-3.0-only 派生。上游代码的著作权归其作者（N3urda、ermilylu 等），派生部分归本仓库维护者。
仓库根的 `LICENSE` 与上游同一份文本，未作改动。

本版相对上游的改动（GPL-3.0 §5(a) 要求的修改声明）：

- 移除 React Native 电视版、iOS 工程与 Node / Docker 内容服务，只保留 Android TV 原生客户端；
- 可构建的 Gradle 工程从 `kotlin-tv/` 移到 `android/`，仓库根只留文档与工具；
- 包名 `com.hongguotv.nativeapp` → `com.hgtv.star`，应用名改为「红果TV」；
- 重做界面（新拟态与十四套材质主题）、发布流程与产物命名。

派生基线为上游 `main` 的 `eea73e6`（`fix: release updater TLS connections off the main thread`，2026-09-23），
此后上游无新提交。逐项改动见 [变更记录](docs/CHANGELOG.md)。

## 2. 第三方依赖声明

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

本仓库的 Android TV 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`
（与仓库根目录 `LICENSE` 同一份文本）。源码仓库：https://github.com/pan8664716/HGTV ，
发布 APK 时同时提供对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。开发期用于逐行核对的上游 JS 参考实现只在本机使用，不随本仓库分发，也不参与应用构建或运行。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0
- Microsoft Fluent Emoji（弹幕表情图，`app/src/main/assets/danmaku_emoji/`，已转为 128px WebP）：
  https://github.com/microsoft/fluentui-emoji — MIT，许可证全文随图放在
  `app/src/main/assets/danmaku_emoji/LICENSE.txt`

应用图标与电视横幅（`app_icon.xml`、`tv_banner.png` 及其派生位图）自本仓库创建起维护，并在上游基础上重绘（2026-10-12 改为扁平白底）。这里的应用不是内容平台官方客户端。

应用内「设置 → 开源许可」弹窗展示的就是本文件的信息摘要：GPL-3.0-only 的许可范围、
源码仓库 `pan8664716/HGTV`、上面的第三方组件清单，
以及「本机记录只保存在这台设备上」这一条边界。改这里时同步改 `MainActivity.licenseText()`。

## 清剧 TV 派生说明（2026-10-18）

本项目复用 HGTV core 与 VideoDataSource，保留上述作者及许可证。新增独立原生电视界面，包名 local.qingju.tv；最低 Android 5.0，Media3 固定 1.4.1；增加旧系统网络状态读取、限量缓冲、倍速和观看位置保存、可见请求错误。未复用 HGTV 原有界面、账号登录、广告或统计组件。对应源码与 GPL-3.0-only 许可证随本项目完整提供。

core构建脚本的派生修改：排除未打包的弹幕图片清单测试，其余核心测试保留。VideoDataSource使用Long计算剩余长度，避免大文件Int溢出。

## 0.2.0界面派生修改（2026-10-19）
新增海报网格、导航、海报详情、收藏与多条历史；新增有界PosterLoader；整合进度条、播放更多菜单、弹窗遥控隔离、前台恢复与播放器回调身份校验。PosterLoader参考HGTV公开ArtworkLoader的普通HTTP图片采样思路，采用独立的缓存与取消实现。版权与GPL-3.0-only范围延续上述声明。

## 青聚 0.3.0界面派生修改（2026-10-19）
应用名称改为青聚。复用HGTV上述固定提交中的HomeScreen、TvComponents、TvStyle、TvTheme和TvSoft（保留com.hgtv.star包路径）；将滚动监听改为兼容API22，合并海报向上导航监听，软阴影缓存限制4MiB。新增四榜剧架、真实排行/热度、分类标签、画面比例选择与记忆；播放器继续使用Media3 1.4.1。相关派生修改均遵循GPL-3.0-only。

## 青聚0.4.0播放修改（2026-10-19）
新增EpisodePrefetch单线程取消/预取管理、单Media3播放器连续队列与最多下一集1MiB暖缓存；修复遥控暂停焦点和按键序列，支持1080P选择，新增TextureView布局及放大裁剪模式。对暂停意图、续播位置和过期请求进行边界处理；所有派生修改延续GPL-3.0-only。
