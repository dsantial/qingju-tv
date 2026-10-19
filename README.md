# 青聚 0.4.0

为小米 L43M7-EA / Android5.1.1 API22 / ARMv7 / 1GB内存制作的电视短剧客户端。当前为侧载测试版，GPL-3.0-only 开源。源码仓库：[dsantial/qingju-tv](https://github.com/dsantial/qingju-tv)。

## 播放修复

- 保留HGTV剧架、排行榜、分类、收藏与历史界面。
- 统一遥控确认和媒体暂停键；菜单先聚焦暂停按钮，暂停时保持控制栏可见；处理按下/抬起和长按重复事件。
- 清晰度上限提供480P/720P/1080P，以接口实际可用资源为准；切画质保留播放位置和暂停意图。
- 使用API22兼容TextureView。画面提供原比例、铺满裁剪、拉伸，以及125%/150%放大裁剪并保存选择；16:9片源在16:9屏幕上前三项可能相同，视频内嵌黑边也会影响效果。
- 同一ExoPlayer保留当前集和下一集队列；后台单线程请求下一集并暖1MiB，跨集不重建播放页/解码器。仅保留一集预取，旧集移出队列后释放；失败10秒后有限重试一次。
- 连续播放仍取决于网络、接口响应及片源编码/分辨率变化，不保证每次切集零间隔；没有下载整剧或合并大文件。

## 遥控器

播放时确认键暂停/继续；菜单键打开控制栏并聚焦暂停，左右选择上一集、下一集、选集、倍速、画面和更多。控制栏隐藏时左右快退/快进10秒，上下呼出控制。画质在「更多 → 清晰度」；画面可选放大裁剪。返回进入详情。

## 验证

0.4.0已在目标电视实测：遥控暂停、1080P、2倍速、画面放大和提前预取跨集均正常，用户确认暂停正常、切集更顺畅。详细范围见[验证说明](docs/VALIDATION.md)。仅抽样验证，不保证所有片源或网络条件下零间隔。

## 构建与安装

JDK17、Android SDK Platform36 / Build Tools36.0.0、Gradle8.14.3。最低API21、target35；纯JVM应用，通过系统解码器播放，兼容本机32位ARM系统。设置ANDROID_HOME或本机local.properties。

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

独立包名local.qingju.tv，可覆盖更新本项目旧版本；不覆盖cn.hongguo.tv或com.hgtv.star。Debug签名用于本机测试，正式分发应使用自行保存的签名密钥。TLS保持正常证书校验。

## 来源

GPL-3.0-only，见LICENSE和NOTICE.md。内容核心和VideoDataSource来自pan8664716/HGTV的d7a70ae0a2811c9f9abe3168328822041f75d144，上游为N3urda/hongguoTV。0.3.0复用同一版本的公开GPL界面组件，海报加载和播放整合为本项目派生实现。

没有复用hongguo-desktop-releases的闭源APK代码或私有签名库。非红果官方客户端；未加入VIP解锁或付费权益绕过。本仓库提供对应完整源码及构建方法。Android5不支持的封面格式会保留占位，不保证每个封面均可解码。

## 已定位的 DNS 过滤兼容问题

如果片单正常而播放失败，并且注册域名 `log5-applog-lf.fqnovel.com` 被解析为 `0.0.0.0` 或 `127.0.0.1`，设备注册会被误拦截。可在确认是误拦截后，仅针对目标电视添加例外（将 `<TV_IP>` 替换为电视实际地址）：

```text
@@||log5-applog-lf.fqnovel.com^$client=<TV_IP>
```

此域同时用于平台注册/日志请求，例外应只针对目标电视的这一域，其他客户端和域名继续过滤。删除这一行即可撤销。项目未内置备用DNS、未绕过家中DNS设置。
