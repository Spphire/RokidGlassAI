# Rokid Photo AI 开发与部署手册

本文档记录当前手机端、眼镜端联调所需的环境、构建、安装、后台运行配置和测试流程。目标是以后重新接设备时可以按步骤复现，不需要再临时查命令。

## 项目结构

```text
common/       手机端和眼镜端共享的协议与图片传输常量
phone-app/    手机端应用，负责蓝牙 SPP 服务、图片接收、AI 请求和 prompt 设置
glasses-app/  眼镜端应用，负责连接手机、拍照、图片压缩发送和 AI 回答显示
scripts/      本地辅助脚本，例如 iQOO 后台配置脚本
doc/          项目开发文档
```

当前主链路：

```text
眼镜单指点击触发拍照
-> 眼镜端裁剪/压缩图片
-> Bluetooth SPP 分片发送给手机
-> 手机端与当前 prompt、知识库设置打包请求 AI
-> 手机端通过蓝牙把状态和回答发回眼镜
-> 眼镜端显示并滚动查看回答
```

## 本地环境

Windows PowerShell 推荐先设置 Android Studio JBR 和 SDK：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\yibo\AppData\Local\Android\Sdk'
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

确认设备：

```powershell
adb devices -l
```

当前常用设备序列号：

```text
iQOO 手机: 10AF1F0PST00419
Rokid 眼镜: 1906092610111484
```

如果同时接了 Quest 或其他 Android 设备，所有安装、启动、日志命令都必须带 `-s <serial>`，避免装错设备。

## 构建

完整构建与测试：

```powershell
.\gradlew.bat --dependency-verification=off :common:testDebugUnitTest :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest :phone-app:assembleDebug :glasses-app:assembleDebug
```

只构建 APK：

```powershell
.\gradlew.bat --dependency-verification=off :phone-app:assembleDebug :glasses-app:assembleDebug
```

产物路径：

```text
phone-app/build/outputs/apk/debug/phone-app-debug.apk
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

## 安装与启动

眼镜端通常可以直接安装：

```powershell
adb -s 1906092610111484 install -r -g .\glasses-app\build\outputs\apk\debug\glasses-app-debug.apk
adb -s 1906092610111484 shell monkey -p com.example.rokidglasses -c android.intent.category.LAUNCHER 1
```

iQOO 手机有时 `adb install` 会卡住或返回空错误。优先尝试普通安装：

```powershell
adb -s 10AF1F0PST00419 shell am force-stop com.example.rokidphone
adb -s 10AF1F0PST00419 install -r -g .\phone-app\build\outputs\apk\debug\phone-app-debug.apk
adb -s 10AF1F0PST00419 shell monkey -p com.example.rokidphone -c android.intent.category.LAUNCHER 1
```

如果普通安装失败，改用推送后本机安装：

```powershell
adb -s 10AF1F0PST00419 push .\phone-app\build\outputs\apk\debug\phone-app-debug.apk /data/local/tmp/rokidphone-debug.apk
adb -s 10AF1F0PST00419 shell pm install -r -g /data/local/tmp/rokidphone-debug.apk
```

注意：iQOO 上 `pm install` 偶尔会超时，但 APK 可能已经安装完成。用下面命令确认：

```powershell
adb -s 10AF1F0PST00419 shell dumpsys package com.example.rokidphone | Select-String -Pattern 'versionName|versionCode|lastUpdateTime'
adb -s 10AF1F0PST00419 shell dumpsys activity activities | Select-String -Pattern 'topResumedActivity|com.example.rokidphone'
```

## iQOO 后台与熄屏运行

手机端已经实现以下 Android 标准后台能力：

- `PhoneAIService` 前台服务，类型为 `connectedDevice`
- `PARTIAL_WAKE_LOCK`，用于熄屏时保持 CPU 处理蓝牙数据
- 忽略电池优化申请入口
- 开机/后台桥接服务恢复能力
- Companion Device 关联入口，用于提高与眼镜类外设的后台优先级
- 可选 1x1 keep-alive overlay，只有获得悬浮窗权限时才会显示

但在 iQOO / vivo / OriginOS 上，标准 Android 机制仍可能被系统冻结。当前实测最关键的人工设置是：

```text
应用信息 -> 电量 -> 允许后台耗电
```

这个开关必须打开。否则手机熄屏或切到桌面后，即使前台服务和 wake lock 都在，系统仍可能冻结应用进程，导致蓝牙连接断开。

建议 iQOO 设置清单：

```text
1. 应用信息 -> 电量 -> 允许后台耗电
2. 设置/电池/后台耗电管理 -> Rokid Photo AI -> 允许后台高耗电或无限制
3. 权限管理/自启动 -> 允许 Rokid Photo AI 自启动
4. 多任务界面锁定 Rokid Photo AI 卡片
5. 如需使用 keep-alive overlay，允许悬浮窗权限
6. App 内点击 Allow Background，批准忽略电池优化
```

可选 ADB 辅助脚本：

```powershell
.\scripts\configure-iqoo-background.ps1 -PhoneSerial 10AF1F0PST00419 -StartApp -WakeScreen
```

该脚本只能配置 Android 标准项和部分运行态状态，不能代替 vivo 系统里的人工后台耗电开关。

## Companion Device 关联

手机端提供 `Associate Rokid Glasses` 按钮。它会优先从已配对蓝牙设备里筛选 Rokid/Glasses/RG_glasses 名称，避免把 iPhone、iQOO 或其他设备当作眼镜候选。

关联后可以用下面命令看状态：

```powershell
adb -s 10AF1F0PST00419 shell dumpsys companiondevice
adb -s 10AF1F0PST00419 logcat -d -v time -s PhoneCompanionBridge PhoneCompanionDeviceSvc PhoneAIService
```

Companion Device 只能提升系统对外设相关后台任务的认可度，不能替代 iQOO 的 `允许后台耗电`。

## 全流程测试

测试前准备：

```text
1. 手机和眼镜都已通过 adb 可见
2. 手机和眼镜 APK 都是最新安装
3. 手机端已启动，前台服务通知可见
4. iQOO 已打开允许后台耗电
5. 手机和眼镜已完成蓝牙配对
```

手机端单独 AI 测试：

```text
1. 打开手机 App
2. 确认 relay URL、model、prompt、知识库选择
3. 设置 Fast preset 或 minimal reasoning，超时先用 60s
4. 点击 Take Phone Photo and Test AI
5. 确认状态经历拍照、压缩、调用 AI、显示回答
```

眼镜到手机再到 AI 的链路测试：

```text
1. 手机端启动 bridge
2. 眼镜端选择并连接手机
3. 眼镜单指点击触发 capture
4. 手机日志应显示收到图片、开始 AI request、AI request done
5. 眼镜端应显示 processing 状态和最终回答
6. 若回答较长，用上下滑动/触控滚动查看，不再依赖一次性分页
```

熄屏/后台测试：

```text
1. 保持手机 bridge 运行
2. 切回手机桌面或熄屏
3. 等待 1-5 分钟
4. 在眼镜端触发 capture
5. 确认蓝牙未断、手机仍收到图片并返回 AI 回答
```

常用日志：

```powershell
adb -s 10AF1F0PST00419 logcat -d -v time -s MainActivity CodexRelayVisionClient PhoneAIService BluetoothSppManager BluetoothPhotoReceiver PhoneCompanionBridge PhoneKeepAliveOverlay
adb -s 1906092610111484 logcat -d -v time -s MainActivity BluetoothSppClient CameraService
```

服务与 wake lock 检查：

```powershell
adb -s 10AF1F0PST00419 shell dumpsys activity services com.example.rokidphone
adb -s 10AF1F0PST00419 shell dumpsys power
adb -s 10AF1F0PST00419 shell dumpsys activity processes com.example.rokidphone
```

重点看：

```text
PhoneAIService 是否 foreground
foregroundServiceType 是否 connectedDevice
是否存在 com.example.rokidphone:GlassesBridge PARTIAL_WAKE_LOCK
进程是否 isFrozen=true
```

## 网络与 AI 配置

当前主 relay：

```properties
CODEX_RELAY_URL=https://api.20021004.xyz
```

配置写在 `local.properties`，模板见 `local.properties.template`。Debug 构建会从 Gradle BuildConfig 读取这些值。

手机请求使用：

```text
POST {CODEX_RELAY_URL}/v1/responses
Authorization: Bearer {CODEX_RELAY_API_KEY}
input_text: 当前 prompt + 知识库上下文
input_image: 眼镜/手机图片 data URL
```

项目现在禁止明文 HTTP：

```text
android:usesCleartextTraffic="false"
network_security_config cleartextTrafficPermitted="false"
```

## 图片传输与显示

共享传输参数位于：

```text
common/src/main/java/com/example/rokidcommon/protocol/photo/PhotoTransferConstants.kt
```

当前默认策略：

```text
目标尺寸: 1280x720 max fit
JPEG quality: 70
Chunk size: 4096 bytes
Transfer timeout: 60s
```

眼镜端会对原图做横向目标裁剪/压缩，降低蓝牙传输体积，同时尽量保留中心文字区域。之前对比过 Q80/Q88/Q95，Q80 与 Q88/Q95 在实际题目识别上差异不大，但传输速度明显更好。

## 常见问题

### AI response empty

优先看手机日志中的 `CodexRelayVisionClient`：

```text
是否真正拿到 HTTP 200
返回 JSON 是否有 output_text 或 message content
图片 data URL 是否过大或为空
prompt 是否为空
```

如果是 `http 502`，通常是 relay 或上游模型暂时失败，先重试或切 fallback。

### received 了但眼镜不显示

检查眼镜端 `BluetoothSppClient` 和 UI 状态日志。当前蓝牙 JSON 文本消息有 64 KiB 上限，超过会断开以避免缓冲区无限增长；AI 回答不应超过 UI 可承载范围，必要时降低 `max_output_tokens`。

### 手机切后台/熄屏后蓝牙断

先确认 iQOO：

```text
应用信息 -> 电量 -> 允许后台耗电
```

再检查前台服务通知、wake lock 和进程冻结状态。若 `isFrozen=true`，说明系统层仍在冻结应用，需要继续补 vivo/iQOO 权限，而不是只改 Android 代码。

### adb install 卡住

iQOO 上可改用：

```powershell
adb push APK /data/local/tmp/...
adb shell pm install -r -g /data/local/tmp/...
```

如果命令超时，马上用 `dumpsys package` 看 `lastUpdateTime`，不要直接假设失败。
