[中文](README.md) | [English](README.en.md)

# 电视相册直推（配合原版 Aves 零改造）

一套纯本地运行的双端 Android 应用：**手机端自动通过局域网或 USB 直连 Android TV**，将选中的照片推送到电视本地磁盘的系统 `MediaStore`（`Pictures/TVAlbums/<相册名>/`），电视上直接安装**原版 Aves 相册**（一行代码不用改）即可自动识别新相册、执行幻灯片轮播或 Android TV 系统屏保。

## 核心特性

- **原版 Aves 零改造**：Aves 监听系统 `MediaStore` 变更。电视接收端通过 `ContentResolver` 原子写入（`IS_PENDING`）与删除照片，新增、同名覆盖、删除旧图都会即时通知 Aves，不留坏缩略图，也不会产生 `IMG_0001 (1).jpg` 重复副本。
- **Wi-Fi 免填 IP 自动发现 + 手动兜底**：打开手机端自动并发执行三层发现，并支持顶部「手动连 IP」直接连接：
  1. 历史电视 IP 秒连（`< 50ms`）；
  2. UDP 子网广播（`53318`）+ `NsdManager`（mDNS `_avestv._tcp.`）；
  3. 若路由器禁用了广播/组播（或电视插网线、手机连 Wi-Fi），320ms 后自动并发探测本网段 `/24` 的接收端口（`< 1s` 扫完）。
- **无 Wi-Fi 也能传：USB 直连双通道**：
  - **手机 USB 直插电视（AOA 2.0 原生模式）**：电视端发起 AOA 握手，手机授权后自动建立多路复用 HTTP 隧道，彻底不依赖 Wi-Fi 与 USB 调试；
  - **USB 网络共享（RNDIS）优先识别**；
  - **USB 隧道直连电脑调试（`adb reverse`）**：配套预览端内置 `AdbUsbWatcher`，插线即自动打通 `127.0.0.1:53319`。
- **电视开机后台常驻，传图免拿遥控器**：电视接收端使用符合 Android 14/15 规范的 `specialUse` 前台服务 + 开机自启，Aves 全屏轮播时后台静默收图。
- **绕过 Android TV 11/12 权限限制**：Android 10+ 上直接通过 `MediaStore` 写入本应用拥有的媒体条目，**无需 `MANAGE_EXTERNAL_STORAGE` 权限，也不依赖系统文件选择器**。
- **手机端 4K JPEG 兼容优化**：默认在推送前读取 EXIF 扶正方向，并将超大图或 `.heic` 转为最高 4K（长边 3840px）高质量 JPEG，兼容老款电视解码且节省电视闪存（可随时关闭改传原图）。
- **电视相册管理与多选批量删除**：手机端可按相册名切换预览电视端照片，支持单张删除、**多选 / 全选批量删除**及清空整本相册（均不影响手机原图）。
- **双端持久化调试日志**：TV 端与手机端均自动记录关键 HTTP 请求、UDP 发现、USB/AOA 状态及异常并滚动落盘；手机端可直接切换查看并复制「电视端日志 / 手机端日志」（`GET/DELETE /api/v1/logs`）。

## 模块结构

- `protocol/`：共享协议、局域网发现报文编解码、`/24` 子网计算、零依赖流式 HTTP/1.1 服务端、UDP 发现应答器、多路复用 USB HTTP 隧道（`UsbHttpTunnel`）、持久化日志（`DebugLogStore`）及桌面预览入口（`PreviewServer`）。
- `web/`：手机页和电视页（`phone.html`、`tv.html`，共用 `app.css` 与 `api.js`）。电脑浏览器与 APK 内 WebView 共用同一套页面。
- `tv/`：Android TV 接收端 APK（`com.avestv.tv`），开机自启前台服务、`UsbAoaHostManager` 与 `MediaStore` 相册仓库。
- `phone/`：Android 手机发送端 APK（`com.avestv.phone`），包含多路发现、系统分享接收、Photo Picker（含 `OPEN_DOCUMENT` / `GET_CONTENT` 兜底）、`UsbAoaAccessoryManager` 与 4K 转码。
- `scripts/`：电脑端 USB 联调脚本（`run-usb-preview.sh`）。
- `docs/`：开发与调试指南（[debug-guide.md](docs/debug-guide.md)）。

## 安装包下载（GitHub Releases）

仓库不再内置 `dist/` 二进制安装包，签名 Release APK 统一发布在 [**GitHub Releases**](https://github.com/jmwasky-creative/aves-tv/releases)：

- 电视端：`tv-release-v0.1.apk`（应用名：**电视相册接收器**）
- 手机端：`phone-release-v0.1.apk`（应用名：**电视相册推送**）

## 本地编译

需要 JDK 17+ 与 Android SDK（API 35）：

```bash
# 运行单元测试并打包 Release APK
./gradlew :protocol:test :phone:testDebugUnitTest assembleRelease

# 或仅打 Debug 包（自带调试签名，可直接安装）
./gradlew assembleDebug
```

签名说明：仓库不包含签名密钥。如需打正式签名的 Release 包，将 `keystore.properties.example` 复制为 `keystore.properties` 并填入本地密钥路径与密码；未配置时 `assembleRelease` 会生成未签名包。

编译产物路径：
- 电视端 Release：`tv/build/outputs/apk/release/tv-release.apk`
- 手机端 Release：`phone/build/outputs/apk/release/phone-release.apk`
- Debug 包：`tv/build/outputs/apk/debug/tv-debug.apk`、`phone/build/outputs/apk/debug/phone-debug.apk`

## 电脑上调试页面与 USB 联调

```bash
./gradlew :protocol:run
```

启动后浏览器打开终端打印的 `phone.html` 与 `tv.html`（默认端口 `53319`）：
- 内置 `AdbUsbWatcher`：手机打开 USB 调试并用数据线接入电脑时，自动建立 `adb reverse tcp:53319`，手机无需连 Wi-Fi 即可自动连上电脑模拟电视；
- 手机页与电视页共用同一内存相册，上传、覆盖、多选删除、清空相册及双端日志会实时同步。
- 更多真机 ADB、WebView 与局域网排查说明见 [docs/debug-guide.md](docs/debug-guide.md)。

## 安装与使用步骤

### 1. 电视端准备（只需配置一次）
1. 在 Android TV 上安装原版 **Aves**（F-Droid 或 GitHub Release，建议 v1.15+，无需改造）。
2. 从 GitHub Releases 下载并安装 `tv-release-v0.1.apk`（应用名：**电视相册接收器**）。
3. 用电视遥控器打开一次「电视相册接收器」，看到右上角显示「运行中 · 自动可发现」即可（Android 8/9 老电视按提示同意存储权限；Android 10+ 零权限弹窗）。
4. 建议在电视系统设置中将「电视相册接收器」设为电池不优化，确保后台长驻。
5. 点界面上的「打开原版 Aves 播放」进入 Aves：
   - 打开 `TVAlbums` 下的对应相册即可遥控器一键开始幻灯片循环播放；
   - 或在 Android TV 系统设置 → 屏保 中选择 Aves，并绑定刚推送的相册，待机自动轮播。

### 2. 手机端日常推图
1. 从 GitHub Releases 下载并安装 `phone-release-v0.1.apk`（应用名：**电视相册推送**）。
2. 选择以下任一连接方式：
   - **Wi-Fi 局域网**：手机与电视连在同一个家庭局域网（路由器请勿开启 AP / 访客隔离），打开 App 自动连上（跨网段可用顶部「手动连 IP」）。
   - **USB 直连**：无 Wi-Fi 时，直接用 USB 数据线将解锁的手机插到电视 USB 口，按弹窗授权即可自动建立直连通道。
3. 两种推送方式任选：
   - **方式 A**：在 App 内选择或输入相册名，点「选择照片并推送到电视」。
   - **方式 B**：直接在手机系统相册里选中照片 → 点「分享」→ 选择「推到电视相册」→ 确认相册名一键推送。
4. 可随时切换「追加照片（同名覆盖）」或「替换整本相册」，在底部网格支持单张删除、**多选批量删除**或清空整本相册（均不影响手机原图）。
