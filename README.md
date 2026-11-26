# 电视相册直推（配合原版 Aves 零改造）

一套纯局域网运行的双端 Android 应用：**手机端自动发现局域网里的 Android TV 并免填 IP 直连**，将选中的照片推送到电视本地磁盘的系统 `MediaStore`（`Pictures/TVAlbums/<相册名>/`），电视上直接安装**原版 Aves 相册**（一行代码不用改）即可自动识别新相册、执行幻灯片轮播或 Android TV 系统屏保。

## 为什么这样设计

- **原版 Aves 零改造**：Aves 监听系统 `MediaStore` 变更。电视接收端通过 `ContentResolver` 原子写入（`IS_PENDING`）与删除照片，新增、同名覆盖、删除旧图都会即时通知 Aves，不留坏缩略图，也不会产生 `IMG_0001 (1).jpg` 重复副本。
- **手机免填 IP 自动直连**：打开手机端自动并发执行三层发现：
  1. 历史电视 IP 秒连（`< 50ms`）；
  2. UDP 子网广播 + `NsdManager`（mDNS `_avestv._tcp.`）；
  3. 若路由器禁用了广播/组播（或电视插网线、手机连 Wi-Fi），300ms 后自动并发探测本网段 `/24` 的接收端口（`< 1s` 扫完）。
- **电视开机后台常驻，传图免拿遥控器**：电视接收端使用符合 Android 14/15 规范的 `specialUse` 前台服务 + 开机自启，Aves 全屏轮播时后台静默收图。
- **绕过 Android TV 11/12 权限限制**：Android 10+ 上直接通过 `MediaStore` 写入本应用拥有的媒体条目，**无需 `MANAGE_EXTERNAL_STORAGE` 权限，也不依赖系统文件选择器**。
- **手机端 4K JPEG 兼容优化**：默认在推送前读取 EXIF 扶正方向，并将超大图或 `.heic` 转为最高 4K（长边 3840px）高质量 JPEG，兼容老款电视解码且节省电视闪存（可随时关闭改传原图）。

## 模块结构

- `protocol/`：共享协议、局域网发现报文编解码、安全文件名清洗、`/24` 子网计算、零依赖流式 HTTP/1.1 服务端引擎与 UDP 发现应答器。
- `web/`：手机页和电视页（`phone.html`、`tv.html`，共用 `app.css` 与 `api.js`）。页面随窗口伸缩，电脑浏览器和 APK 里的 WebView 用的是同一套文件。
- `tv/`：Android TV 接收端 APK（`com.avestv.tv`），开机自启前台服务 + `MediaStore` 相册仓库。状态页由 WebView 加载 `tv.html`，改名、开机自启和打开原版 Aves 走 Kotlin 桥。
- `phone/`：Android 手机发送端 APK（`com.avestv.phone`）。页面由 WebView 加载 `phone.html`。发现电视、系统分享、Photo Picker 和 4K 转码留在 Kotlin；相册列表、上传和删除走原来的 HTTP 接口。

## 安装包

可直接安装的签名 Release APK 在 `dist/`：

- 电视端：`dist/tv-release.apk`（应用名：**电视相册接收器**）
- 手机端：`dist/phone-release.apk`（应用名：**电视相册推送**）

签名钥匙不在仓库里。要自己打已签名的 Release 包，把 `keystore.properties.example` 复制成 `keystore.properties`，填上本地钥匙的路径和密码。没有这份文件时，`assembleRelease` 会打出未签名的包。

## 编译

需要 JDK 17+ 与 Android SDK（API 35）：

```bash
./gradlew test assembleRelease
```

编译产物：

- 电视端 Release：`tv/build/outputs/apk/release/tv-release.apk`
- 手机端 Release：`phone/build/outputs/apk/release/phone-release.apk`
- 调试包：`./gradlew assembleDebug`，输出 `tv-debug.apk` 与 `phone-debug.apk`

## 电脑上调页面

```bash
./gradlew :protocol:run
```

浏览器打开终端打印的手机页和电视页。手机页拉窄是单列，拉宽后内容居中、照片网格变多列；电视页在宽窗口里按钮横排，窗口变窄后按钮改成竖排，键盘焦点是亮蓝色。本机预览固定连到 `127.0.0.1:53319`：选图用文件框，打开 Aves 和开机自启只更新页面状态。追加、同名覆盖、整本替换、删一张、清空相册会同时反映在两张页面上。

## 安装与使用步骤

### 1. 电视端准备（只需配置一次）
1. 在 Android TV 上安装原版 **Aves**（F-Droid 或 GitHub Release，建议 v1.15+，无需改造）。
2. 在 Android TV 上安装 `dist/tv-release.apk`（应用名：**电视相册接收器**）。
3. 用电视遥控器打开一次「电视相册接收器」，看到右上角显示「运行中 · 自动可发现」即可（Android 8/9 老电视按提示同意存储权限；Android 10+ 零权限弹窗）。
4. 建议在电视系统设置中将「电视相册接收器」设为电池不优化，确保后台长驻。
5. 点界面上的「打开原版 Aves 播放」进入 Aves：
   - 打开 `TVAlbums` 下的对应相册即可遥控器一键开始幻灯片循环播放；
   - 或在 Android TV 系统设置 → 屏保 中选择 Aves，并绑定刚推送的相册，待机自动轮播。

### 2. 手机端日常推图（零手动填 IP）
1. 在 Android 手机上安装 `dist/phone-release.apk`（应用名：**电视相册推送**）。
2. 确保手机与电视连在同一个家庭局域网（路由器请勿开启 AP 隔离 / 访客隔离）。
3. 两种推送方式任选：
   - **方式 A**：打开「电视相册推送」，顶部自动发现并连上电视后，选择或输入相册名，点「选择照片并推送到电视」。
   - **方式 B**：直接在手机系统相册里选中照片 → 点「分享」→ 选择「推到电视相册」→ 确认相册名一键推送。
4. 可在手机端随时选择「追加照片（同名覆盖）」或「替换整本相册」，也可以直接在底部预览并删除电视上的旧图或清空整本相册（不影响手机原图）。
