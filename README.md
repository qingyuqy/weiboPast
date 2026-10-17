# WeiboPast — 微博备份与回忆录

围绕「备份自己的微博 → 导出成可存档/可打印的成品」这一目标的三套独立实现。
三者互不依赖，可分别使用。

```
weiboPast/
├── android-app/        ① Android 应用（Java）：登录 → 时间线 → 杂志式 PDF
├── browser-extension/  ② 浏览器扩展 / 油猴脚本：全量备份 → JSON / HTML / PDF
└── python-tools/       ③ Python / PowerShell 后处理脚本：补图、压缩、合并 PDF
```

---

## ① android-app/ — Android 原生应用

包名 `com.qingyu.weibopast`，Java + Gradle，minSdk 22 / targetSdk 34。

**功能**：WebView OAuth 登录 → 抓取本人时间线 → 用手绘 `PdfDocument` 生成杂志风格 PDF
（封面页、按日期分组、图片网格、转发/评论/赞统计）。

**构建**：用 Android Studio 打开 `android-app/`，或
```bash
cd android-app && ./gradlew assembleRelease
```
已构建的 APK 见 `android-app/app/app-release.apk`。

> 注意：`LoginActivity` 中硬编码了微博 `client_secret`，`PdfExporter` 的卡片绘制顺序存在图片被背景覆盖的问题，详见下方「已知问题」。

## ② browser-extension/ — 浏览器扩展（Manifest V3）/ 油猴脚本

版本 0.2.14，同一份脚本同时支持 Chrome/Edge 扩展安装与 Tampermonkey 安装。

**抓取**：`weibo.com/ajax` 接口全量翻页（`since_id`）、可选时间范围、长文展开、
一级评论（5/10/20 条）、收藏、关注列表；实时写入 **IndexedDB**，支持断点续传、
暂停/停止；带指数退避重试与验证码识别。

**导出**：JSON / 可打印 HTML（Ctrl+P 存 PDF，A4 打印样式）/ 打印 PDF 窗口 / 图片链接 CSV。
图片可选「链接」或「内嵌离线」，头像自动内嵌并压缩。

```
browser-extension/
├── manifest.json          Chrome 扩展清单（MV3）
├── weibo-export.user.js   主脚本（也是油猴脚本）
├── main-world.js          在页面主世界读取 uid 写入 DOM
├── README.md              详细使用说明
└── test/
    ├── weibo-export.test.js  Node 单元测试（解析/渲染）
    └── harness.html          浏览器手动测试挂载页
```

**测试**：
```bash
cd browser-extension && node test/weibo-export.test.js
```

## ③ python-tools/ — 导出后处理脚本

对插件「导出 HTML / 打印 PDF」的产物做离线加工，按需单独运行：

| 文件 | 作用 |
|---|---|
| `embed-avatars.py` | 把 HTML 中的微博头像下载并转成 base64 dataURL 内嵌（解决图床防盗链导致离线不显示） |
| `compress-avatars.py` | 用 Pillow 把内嵌头像缩到 160px / JPEG q85，避免 HTML 体积过大 |
| `merge-pdf-helper.py` | 用 pypdf 合并多个 PDF |
| `merge-pdf.ps1` | Windows 驱动脚本：调用无头 Edge 把多个 part HTML 逐个打印为 PDF，再调用 `merge-pdf-helper.py` 合并 |

依赖：`pip install pypdf pillow`；`merge-pdf.ps1` 需 Edge 与 Python 在 PATH 中。

---

## 已知问题（源自代码审查）

**android-app**
- `LoginActivity.java` 明文硬编码 `client_secret`，正式分发前应移除或改为服务端换取。
- `PdfExporter.java` 在绘制完卡片内容后又用不透明白色背景重绘整张卡片，会覆盖图片与统计行。
- 抓取到的微博仅存内存，退出应用即丢失，无本地持久化。
- 列表未调用 `setUserInfo()`，所有条目显示 "Me"。

**browser-extension**
- 导出 HTML 时按条数分卷（内嵌模式 120 条/卷），需配合 `merge-pdf.ps1` 合并。
- 缓存按域名隔离，`weibo.com` 与 `www.weibo.com` 的 IndexedDB 不互通。

## 整理说明

本目录已按代码类型分目录整理，并清理了以下可再生产物：

- Gradle 依赖缓存 / daemon / native / wrapper 发行版（约 1.67 GB，构建时自动重新下载）
- `app/build/` 构建中间产物（30 MB）
- `.gradle/`、`.idea/` IDE 生成文件
- UIAutomator 调试转储 `ui*.xml`（5 个）
- 根目录与 `weibo-export-extension/` 中内容完全相同的重复脚本 `weibo-export.user.js`
- 损坏的调试截图 `screenshot_main.png`
- `.git` 中 4891 个不可达松散对象（约 1.7 GB，不影响任何提交历史）
