# aves-tv 代码地址与调试指南

## 1. 代码地址

| 项目 | 值 |
|---|---|
| 远程仓库 | https://github.com/jmwasky-creative/aves-tv |
| 本地路径 | `/Users/a1/workplace/agy/aves-tv` |
| 默认分支 | `main` |

```bash
git clone https://github.com/jmwasky-creative/aves-tv /Users/a1/workplace/agy/aves-tv
```

### 模块结构

| 目录 | 说明 |
|---|---|
| `protocol/` | 共享协议、HTTP 服务（`StreamingHttpServer`）、UDP 发现应答（`UdpDiscoveryResponder`）、桌面预览入口（`PreviewServer.kt`） |
| `web/` | 手机页 `phone.html`、电视页 `tv.html`，共用 `app.css`、`api.js`；APK 内的 WebView 与电脑浏览器用同一套文件 |
| `tv/` | Android TV 接收端 APK（`com.avestv.tv`） |
| `phone/` | Android 手机发送端 APK（`com.avestv.phone`） |
| `dist/` | 仓库自带的已签名 Release 安装包 |

### 关键源码

| 文件 | 作用 |
|---|---|
| `protocol/src/main/kotlin/com/avestv/protocol/PreviewServer.kt` | 电脑端预览入口，启动 HTTP + UDP 发现 |
| `protocol/src/main/kotlin/com/avestv/protocol/StreamingHttpServer.kt` | HTTP 接口：`/api/v1/info`、相册、上传、删除 |
| `protocol/src/main/kotlin/com/avestv/protocol/DiscoveryProtocol.kt` | 端口常量、子网计算、文件名清洗 |
| `phone/src/main/kotlin/com/avestv/phone/PhoneMainActivity.kt` | 手机端 WebView 壳、原生桥、选图与上传 |
| `phone/src/main/kotlin/com/avestv/phone/TvLanScanner.kt` | 三层局域网发现 + 手动 IP 连接 |
| `phone/src/main/kotlin/com/avestv/phone/TvApiClient.kt` | 手机端 OkHttp 客户端（已设置 `Proxy.NO_PROXY`） |
| `tv/src/main/kotlin/com/avestv/tv/ReceiverForegroundService.kt` | 电视端前台服务（HTTP、UDP、mDNS） |
| `web/phone.html` | 手机页（含「重新搜索」「手动连 IP」） |

### 固定端口

| 端口 | 协议 | 用途 |
|---|---|---|
| `53319` | TCP | HTTP 接收服务 |
| `53318` | UDP | 局域网发现（探测报文 `AVESTV_DISCOVER_V1`，应答 `AVESTV_HERE_V1`） |

## 2. 本地环境

| 项目 | 值 |
|---|---|
| JDK | 17+（本机 Corretto 17.0.11） |
| Android SDK | `/Users/a1/Library/Android/sdk`（API 35，build-tools 34.0.0 与 35.0.0） |
| `adb` | `/Users/a1/Library/Android/sdk/platform-tools/adb` |
| `apksigner` | `/Users/a1/Library/Android/sdk/build-tools/35.0.0/apksigner` |

项目根目录需要 `local.properties`（已被 `.gitignore` 忽略）：

```properties
sdk.dir=/Users/a1/Library/Android/sdk
```

> 本机 `phone/build.gradle.kts` 与 `tv/build.gradle.kts` 已补充 `import java.util.Properties`，否则 Gradle 配置阶段会报 `Unresolved reference: util`。

## 3. 电脑端预览（模拟电视，无需真机）

```bash
cd /Users/a1/workplace/agy/aves-tv
./gradlew :protocol:run
```

启动后终端会打印：

```
AvesTV preview
  phone  http://127.0.0.1:53319/phone.html
  tv     http://127.0.0.1:53319/tv.html
  lan    http://<电脑局域网IP>:53319
  udp    listening on 53318
```

- 浏览器打开 `phone.html` 和 `tv.html`，两个页面共用同一个内存相册，追加、覆盖、删除会同时反映。
- 电脑页面里「局域网地址」固定显示 `127.0.0.1:53319`，手机要连的是终端里 `lan` 那一行的地址。
- 首次运行会下载 Gradle 8.11.1 和依赖，约需几分钟。
- 停止服务：终止该 Gradle 进程（终端里 `Ctrl+C`）。

## 4. 单元测试与打包

```bash
export ANDROID_HOME=/Users/a1/Library/Android/sdk

# 单元测试
./gradlew :protocol:test :phone:testDebugUnitTest

# Debug 包（自带调试签名，可直接安装）
./gradlew assembleDebug
```

产物：

- 手机端：`phone/build/outputs/apk/debug/phone-debug.apk`
- 电视端：`tv/build/outputs/apk/debug/tv-debug.apk`

校验签名：

```bash
/Users/a1/Library/Android/sdk/build-tools/35.0.0/apksigner verify --verbose phone/build/outputs/apk/debug/phone-debug.apk
```

Release 包：复制 `keystore.properties.example` 为 `keystore.properties` 并填写本地密钥后执行 `./gradlew assembleRelease`。没有该文件时会产出未签名包，不能直接安装。

## 5. 真机调试方式

`adb` 是 Android 调试的底层通道。按场景选用：

### 5.1 USB 调试（最稳，可绕开 Wi-Fi 隔离）

1. 手机开启「开发者选项 → USB 调试」，用数据线连电脑并点「允许」。
2. 检查与安装：

```bash
ADB=/Users/a1/Library/Android/sdk/platform-tools/adb
$ADB devices -l
$ADB install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB logcat -s AndroidRuntime:E
```

3. 手机与电脑不在同一 Wi-Fi 或 Wi-Fi 有客户端隔离时，运行仓库自带的一键脚本自动建立端口映射并启动电脑预览服务：

```bash
./scripts/run-usb-preview.sh
```

脚本会自动对在线的 ADB 设备执行 `$ADB reverse tcp:53319 tcp:53319`。新版手机端内置了 Layer 0 USB 隧道自动探测（`127.0.0.1:53319`），打开 App 后无需 Wi-Fi 也会自动连上电脑预览端。

### 5.1.1 手机 USB 直插电视（AOA 2.0 原生通道）

除 USB 连电脑外，手机也可通过普通 USB 数据线**直接插在 Android TV 的 USB 口上**，完全不依赖 Wi-Fi：

1. 电视端 `ReceiverForegroundService`（`UsbAoaHostManager`）自动检测接入的手机并发起 AOA 2.0 握手；
2. 手机端（`UsbAoaAccessoryManager`）弹窗授权后启动 `UsbTunnelClientBridge`，将原有 HTTP 接口透明跑在 USB 上；
3. 手机端扫描自动优先选中 `USB 直连（AOA）`，即可直接选图推送到电视端原版 Aves。

### 5.2 无线 ADB（Android 11+，手机和电脑须在同一个不隔离的 Wi-Fi）

1. 「设置 → 关于手机」连点版本号 7 次，打开开发者选项。
2. 打开「无线调试」，进入详情页：主页面显示的是**连接端口**，点「使用配对码配对设备」得到**配对端口**和 6 位**配对码**（弹窗保持打开）。
3. 电脑执行：

```bash
$ADB pair <手机IP>:<配对端口> <6位配对码>
$ADB connect <手机IP>:<连接端口>
$ADB devices -l
```

注意：
- 手机自己开热点时，系统不允许打开「无线调试」。
- 电视或电视盒子：在开发者选项打开「网络 ADB 调试」，然后 `adb connect <电视IP>:5555`。
- Android 10 及以下：先 USB 连接执行 `adb tcpip 5555`，拔线后 `adb connect <手机IP>:5555`。
- 实测教训：电脑 `10.0.173.176` 与手机 `10.0.164.170` 在不同网段，`adb pair` 报 `protocol fault`，需换网络或改用 USB。

### 5.3 WebView 调试

`phone` 与 `tv` 都是 Kotlin 壳 + WebView 加载 `web/` 页面：

- 改页面和交互：先在电脑浏览器里调（`./gradlew :protocol:run`），刷新即可，不用每次重打 APK。
- 真机里的 WebView：Debug 包中调用 `WebView.setWebContentsDebuggingEnabled(true)` 后连上 ADB，在电脑 Chrome 打开 `chrome://inspect/#devices`，可查看 Console、DOM 和 Network。（当前代码尚未开启，需要时再加。）

## 6. 局域网发现原理与排查

手机端按三层并发发现电视：

1. 历史 IP 秒连（上次连过的电视）。
2. UDP 广播（`53318`）与 mDNS（`_avestv._tcp.`）。
3. 300ms 后仍未发现时，对手机所在 `/24` 网段逐个探测 `53319`。

以上都找不到时，使用页面里的「手动连 IP」直接输入电视或电脑的 IP（可带端口，如 `192.168.67.188:53319`），连过一次后 App 会记住。

### 6.1 排查清单

先做一个最直接的测试：**用手机浏览器打开 `http://<电脑或电视IP>:53319/tv.html`**。

| 现象 | 结论 |
|---|---|
| 能打开 | 网络互通。自动发现失败多半是广播被过滤，或手机与电视不在同一个 `/24`，用「手动连 IP」即可 |
| 一直转圈后 timeout | 数据包被静默丢弃，见下表 |
| 立即报 Connection refused | 网络通，但电视端服务没启动或端口不对 |

timeout 的常见原因：

| 原因 | 处理 |
|---|---|
| 手机或电脑开了代理 / VPN（尤其 TUN 模式） | 临时关闭后重试 |
| macOS 未授予「本地网络」权限 | 系统设置 → 隐私与安全性 → 本地网络，打开 Antigravity、终端、`java` |
| Wi-Fi 开启了客户端隔离（AP 隔离、访客网络） | 换普通路由器 Wi-Fi，或改用 USB（`adb reverse`），或让 Mac 连手机热点 |
| 网段不同（如光猫与路由器两层网络） | 让设备接到同一台路由器 |
| 电视网线接在光猫的 IPTV 口 | 改插普通上网口或路由器 LAN 口 |

### 6.2 网段说明

- 子网掩码 `255.255.240.0`（`/20`）时，`192.168.64.1` ~ `192.168.79.254` 都是同一网段。例如手机 `192.168.65.8` 与电脑 `192.168.67.188` 属于同一局域网，不需要改成相同 IP（相同 IP 会冲突）。
- 第三层 HTTP 扫描只扫手机所在 `/24`，覆盖不到同一 `/20` 里的其他 `/24`，此时用「手动连 IP」。
- 电视插网线、手机连 Wi-Fi：只要两者在同一子网且路由器不隔离有线与无线，就能被扫描到。

### 6.3 排查命令（电脑端）

```bash
ifconfig en0                       # IP、子网掩码、广播地址
route -n get default               # 默认网关
scutil --proxy                     # 系统代理
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate   # 防火墙
arp -n <手机IP>                     # 能否在二层看到手机
curl -s -m 2 http://<电脑IP>:53319/api/v1/info                     # HTTP 服务自检
```

UDP 自检：

```bash
python3 - <<'EOF'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(1.0)
s.sendto(b"AVESTV_DISCOVER_V1", ("<电脑IP>", 53318))
print(s.recvfrom(4096))
EOF
```

## 7. 已知问题记录

| 问题 | 原因 | 处理 |
|---|---|---|
| 「选择照片并推送到电视」点不动 | 原先未连上电视时按钮被 `disabled` | 已改为可点并提示，增加「手动连 IP」入口 |
| 手机搜不到电脑模拟电视 | 预览服务原先只启动 HTTP，没有 UDP 应答；手机探测超时偏短且可能走系统代理 | 已启动 UDP 应答，探测超时放宽到 800ms/1s，客户端设置 `Proxy.NO_PROXY` |
| 相册选择器打不开 | 固定 `PickMultipleVisualMedia(100)` 在部分机型不兼容 | 已改为默认上限，并增加 `ACTION_OPEN_DOCUMENT`、`ACTION_GET_CONTENT` 兜底 |
| 手机浏览器访问电脑地址 timeout | 代理 / VPN、macOS 本地网络权限，或 Wi-Fi 客户端隔离 | 见 6.1 |
| Gradle 配置报 `Unresolved reference: util` | `android {}` 块内 `java` 被解析为扩展而非包 | 已加 `import java.util.Properties` |
