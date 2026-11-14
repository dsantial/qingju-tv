# 来源、许可与修改声明

青聚（Qingju TV）是GPL-3.0-only派生项目，许可证全文见[LICENSE](LICENSE)。本项目并非内容平台官方客户端。

## 派生来源

- [pan8664716/HGTV](https://github.com/pan8664716/HGTV)，固定提交 `d7a70ae0a2811c9f9abe3168328822041f75d144`：内容核心、VideoDataSource、HomeScreen、TvComponents、TvStyle、TvTheme、TvSoft。
- HGTV上游为[N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)。原作者著作权及其第三方声明保留，完整历史声明见[UPSTREAM-NOTICE](docs/UPSTREAM-NOTICE.md)。该历史声明描述上游完整应用，部分功能和资产未包含在青聚中。
- 核心中的签名及媒体处理移植来源，遵循上游声明： [woshishiq1/drpys](https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715)（GPL-3.0）。

## 青聚修改（2026-10-18至2026-10-19）

新增独立Android电视界面与包名local.qingju.tv，支持API21以上；Media3固定1.4.1以兼容Android5。新增海报加载、观看历史、收藏、详情与遥控交互；复用并调整HGTV剧架和样式，替换API23滚动监听、限制阴影缓存。

播放器新增倍速、续播、TextureView画面模式、1080P选择、暂停按键序列处理、单播放器队列及可取消的下一集1MiB预取。修复VideoDataSource剩余长度的64位计算。未打包上游弹幕图片资产，因此核心测试排除对应图片清单测试，其余测试保留。

上述修改均按GPL-3.0-only提供。未使用hongguo-desktop-releases闭源APK代码、私有签名库，未新增VIP解锁。应用本地用户数据、签名私钥和家庭网络记录不属于公开源码。

## 实际构建依赖

- Kotlin、AndroidX Media3、OkHttp/Okio：Apache-2.0。
- Bouncy Castle、jsoup：MIT。
- Android desugar_jdk_libs：GPL-2.0 with Classpath Exception。
- org.json（JVM核心工具/测试；APK使用Android内置实现）：上游JSON许可声明。
- JUnit（测试）：EPL-1.0。

依赖版本见Gradle构建文件。详细上游声明保留于docs/UPSTREAM-NOTICE.md；本项目未打包ZXing或Fluent Emoji图片资产。


## 单集播放签名适配（2026-11-13）

`PlayerSignature.kt`、`PlayerSignatureTest.kt`、测试金标 `player-signing.json` 以及播放模型解析 `PlaybackModels.kt` 移植或调整自
[N3urda/hongguoTV](https://github.com/N3urda/hongguoTV/tree/21ec92206fbfca1d88127c1a4bd1e86a1bab79bf/kotlin-tv)，
固定提交 `21ec92206fbfca1d88127c1a4bd1e86a1bab79bf`，遵循 GPL-3.0-only；青聚修改包名以接入已有核心模块，并精简模型解析以保持现有播放队列与 Android 5 兼容。
测试金标由上游使用独立 Go 实现、固定时钟与 nonce 生成，不含真实设备身份或媒体地址。

上游 Argus/Simon 结构参考并改写自 [tr4cex/TikTok-Encryption](https://github.com/tr4cex/TikTok-Encryption)（MIT），保留其完整许可如下：

```text
MIT License

Copyright (c) 2024 TikTok Encryption Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

EDUCATIONAL USE DISCLAIMER:
This software is intended for educational and research purposes only.
Users are responsible for ensuring their use complies with applicable laws
and terms of service. The authors disclaim any responsibility for misuse
of this software.
```
