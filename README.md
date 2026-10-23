# TTSNover Android 客户端

网页阅读器（`/home/liumanu/code/webreader`，FastAPI + 8848）的原生 Android 客户端。
纯框架 Java（`HttpURLConnection` + `org.json` + `MediaPlayer` + `MediaSession`），
**不依赖 Gradle / AndroidX / OkHttp / Kotlin**，用 SDK 自带的 `aapt2 + javac + d8 + apksigner` 直接出包。

## 已实现

| 模块 | 说明 |
|---|---|
| 登录 / 注册 | 服务器地址可填（首次启动手输，支持 `https://域名` 或 `http://IP:8848`），Bearer token 存 SharedPreferences |
| 书架 | 分页拉取、显示进度/章数/来源、下拉刷新按钮、**长按删除**（含确认） |
| 目录 | 全书章节列表、当前章高亮、继续阅读 |
| 阅读 | 滚动正文、上一章/下一章、退出自动保存进度（章节 + 百分比） |
| 搜书 | 走 `/api/src/search`（服务端 SSE 聚合后的普通 JSON），显示「命中 X 本 / 几个源有结果 / 耗时」，点一本即抓目录入架 |
| 书源 | 「我的 → 导入书源 JSON」，选文件即导入（Legado 导出文件可直接用） |
| 朗读 | `ForegroundService` + `MediaSession`：**熄屏继续读**，通知栏可暂停/继续/上一章/下一章/停止；边合成边播（预取下一段），一章读完自动续下一章；进度回写服务端 |
| 引擎 | 小米 MiMo（预置/设计/克隆）、Fish Audio、硅基流动、**跟随网页版自定义接口**；Key 只存服务端（掩码回显） |
| 账号 | 设备管理（列出登录设备、点一条踢下线）、退出登录、版本检查 |

## 构建

```bash
cd ~/code/ttsnover-app
./build.sh            # 产物：dist/TTSNover-1.0.0.apk
```

依赖：`~/android-sdk`（platforms/android-34、build-tools/35.0.0、platform-tools）。
**d8 必须用 35.0.0**：34.0.0 在 JDK 21 上 dex 多个类会内部 NPE（`R0.a` 空指针）。

签名：`keystore/ttsnover.jks`，alias `ttsnover`，口令 `ttsnover2026`。
**升级必须用同一把 key 签名**，否则覆盖安装会失败（用户数据会丢），这个文件要留好。

## 安装

```bash
adb install -r dist/TTSNover-1.0.0.apk
```

或把 APK 传到手机点安装（需允许「安装未知来源应用」）。

首次打开：填服务器地址 → 登录或注册 → 「我的 → 导入书源 JSON」导入书源 → 「搜索」找书。

## 朗读怎么配

1. 「我的 → 朗读引擎」选引擎；
2. 填该引擎的 API Key → 保存 Key（服务端 AES-GCM 加密，App 只拿得到掩码）；
3. 填音色 ID（Fish 是 32 位 hex；小米是预置名如 `冰糖`）和语速（默认 1.18）→ 保存朗读设置；
4. 「试听一句」确认音色，然后阅读页点「朗读」。

选「跟随网页版设置」时不发 `provider`，完全用网页版里那套 url/headers/body（适合自建/局域网 TTS）。

## 实测记录（模拟器 Pixel 5 / Android 14）

- 注册 → 登录 → 书架 → 搜索「沧元图」（91 源，命中 262 本 / 10 源有结果 / 12s）→ 入架（唐三中文 802 章）→ 目录 → 阅读 → 朗读
- 朗读：逐段合成（每段 86~129 字）、预取下一段、**熄屏 25s 仍在合成**、通知栏媒体控件可用、暂停/继续/停止生效、跨章自动续读、进度回写（读到第 2 章）
- 试听、设备管理（9 条会话，可踢）、书架长按删除（6 本 → 5 本）均正常
- 未验证：真实 Fish/MiMo Key 的音质（本机没有可用余额的 key）；HTTPS 域名（需要证书环境）

## 已知限制

- 阅读区是**滚动**模式，没有网页版的三种翻页/无缝拼接；需要的话下一步补。
- 没有离线缓存（正文每次从服务端取，服务端有章节缓存）。
- 搜书成败取决于书源质量：猫眼看书等源自身 token 过期/需 jsLib，搜得到但抓不到目录（服务端已有清晰报错）。
- 通知权限被拒时，前台服务仍能跑，但熄屏容易被系统回收，建议允许通知。
