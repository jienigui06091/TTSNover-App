# TTSNover Android 客户端（本机模式）

**1.1.0 起不再需要服务器**：书源、书架、章节缓存、阅读进度、朗读配置、API Key 全部存在手机本地，
打开就能用，只配「书源 + TTS」两件事 —— 和以前的读书 App 一样。

纯框架 Java（`HttpURLConnection` + `org.json` + `MediaPlayer` + `MediaSession`），
不依赖 Gradle / AndroidX / OkHttp / Kotlin，用 SDK 自带的 `aapt2 + javac + d8 + apksigner` 直接出包。
唯二第三方库：`jsoup`（HTML/CSS 解析，MIT）和 `Rhino`（跑书源里的 `@js:` / `{{}}` 表达式，MPL 2.0）。

## 本机模式做了什么

| 模块 | 实现 |
|---|---|
| 书源引擎 | `RuleEngine.java`：把网页版 Node 引擎（`srce/engine.js`）等价移植到 Java —— JSON 路径（含 `..` 递归、`[*]`、`[?(@.x==v)]`）、jsoup CSS 选择器（`@` 分段、`!0` 排除、`[0:5]` 切片）、`##正则##替换`、`{{表达式}}`、`@js:` 全部支持；`java.ajax/base64/md5/timeFormat/put/getString` 垫片齐活 |
| 抓取 | `Net.java`：手动跟 301/302/303/307/308（`HttpURLConnection` 自动跟跳在 POST/streaming 下会抛 `cannot retry due to redirection`）、Cookie 保持、gzip、gbk/gb18030 解码、非法字符 URL 兜底 |
| 书架 / 进度 / 缓存 | `Store.java`：手机本地 SQLite（`books` / `chapters` / `sources` / `settings`），正文按需抓取并缓存，退出自动存进度 |
| 朗读 | `TtsClient.java`：**手机直连上游**。小米 MiMo（预置/设计音色）走 `api.xiaomimimo.com`，Key 只存本机 SharedPreferences；也可切「自定义接口」填自己的 TTS 服务（`{{text}}`/`{{voice}}`/`{{speed}}` 占位）。合成结果按内容哈希缓存成 mp3，重听不再请求上游 |
| 后台朗读 | `TtsService`：前台服务 + MediaSession，熄屏/切后台继续读，通知栏可暂停/继续/上下章/停止，一章读完自动续下一章 |
| 书源管理 | 「我的 → 书源管理」：91 个源多选启用/停用、导出成 JSON 到 `Android/data/com.ttsnover.reader/files/sources.json`；「导入书源 JSON」直接吃 Legado 导出的文件 |
| 登录 | 没有。没有账号、没有 token、没有服务器地址 |

## 构建

```bash
cd ~/code/ttsnover-app
./build.sh            # 产物：dist/TTSNover-1.1.0.apk
```

依赖：`~/android-sdk`（platforms/android-34、build-tools/35.0.0、platform-tools）+ `libs/*.jar`（已随仓库提供）。
**d8 必须用 35.0.0**：34.0.0 在 JDK 21 上 dex 多个类会内部 NPE。

## 安装 / 首次使用

```bash
adb install -r dist/TTSNover-1.1.0.apk
```

1. 「我的 → 导入书源 JSON」选 Legado 导出的书源文件（或先用网页版 `/api/sources/export` 导出）
2. 「我的 → 朗读引擎」选小米 MiMo，粘贴自己的 Key，保存；音色/语速按需改
3. 「搜索」输书名或作者 → 点一本入架 → 目录 → 读；长按目录里的章节可直接从该章开始朗读

## 与网页版的关系

网页版（`/home/liumanu/code/webreader`）仍然可以用，提供多用户、跨设备同步、服务端 TTS 代理。
两者数据结构互不相通：本机模式的进度只在这台手机上。想要多端同步就继续用网页版/服务器版 APK（1.0.0）。

## 已知限制

- `@js:` 规则的源依赖 Rhino，首次执行稍慢（每个源首次搜索多 100~300ms）
- 需要站点用 JS 动态加载正文的源（如 `xuanhuange.info` 的 `read2()`）抓不到正文 —— 网页版同样抓不到
- 手机网络直连 `api.fish.audio` 会 `Network is unreachable`（实测），所以本机模式默认小米 MiMo；Fish 音色请走「自定义接口」指向你自己的代理
- 搜索是并发 8 源、总预算 12 秒，源特别多时一轮只跑得完 30~40 个，再搜一次会轮到后面的源
