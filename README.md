# Rokid Photo AI

Rokid Photo AI is a trimmed phone + glasses project for one task: capture an image on Rokid glasses, send it to the phone over Bluetooth SPP, ask a Codex++ relay vision model with a preset prompt, and show the answer back on the glasses.

The phone app also has a direct camera test path, so you can verify the AI relay without wearing or connecting the glasses.

## Current Scope

In scope:

- Glasses trigger photo capture from touch/key or a phone request.
- Glasses compress the photo to the shared target profile and send it in Bluetooth chunks.
- Phone receives, verifies, and calls the Codex++ relay `/v1/responses` endpoint.
- Phone stores a preset prompt and adjustable AI parameters.
- Phone camera can simulate the glasses image profile for relay testing.
- Glasses display processing status, errors, and scrollable AI results.

Out of scope:

- Voice input, STT, TTS, chat history, Room database, and multi-provider AI switching.
- Standalone glasses-only AI inference.
- Real-time video streaming or AR overlays.

## Modules

```text
RokidPhotoAI/
  common/       Shared message types and Bluetooth photo packet protocol.
  phone-app/    Android phone app: UI, foreground bridge, photo receiver, Codex++ relay client.
  glasses-app/  Rokid glasses app: Bluetooth client, camera capture, compression, result display.
  app/          Archived upstream integrated app kept only as reference; not included by Gradle.
```

Gradle includes only `:common`, `:phone-app`, and `:glasses-app`.

## Phone App

Main functions:

- Edit and persist the default vision prompt.
- Select a local knowledge-base context for AI requests: `Software Engineering`, `French TCF/TEF`, or `None`.
- Tune request parameters: reasoning effort, verbosity, output tokens, upload image side, JPEG quality, and timeout.
- Run `Take Phone Photo and Test AI`, which compresses the phone image like a glasses image before calling AI.
- Run a foreground Bluetooth bridge service so the phone can keep receiving photos in the background.
- Ask connected glasses to capture and analyze a new photo.

### Background and Screen-off Bridge

The phone side keeps the glasses link alive with a foreground `connectedDevice` service and a partial wake lock. The app declares the Bluetooth runtime permissions, notification permission, foreground service permissions, `WAKE_LOCK`, boot restart receiver, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

For the iQOO/vivo test phone, Android's standard permissions are not enough by themselves. vivo can still freeze a foreground app after the screen turns off, which disables the wake lock at the system layer. Before testing glasses communication with the screen off:

1. Launch the phone app once and allow Bluetooth and notification permissions.
2. Tap `Allow Background` and approve ignoring battery optimizations.
3. Tap `Power Manager` and enable the app in vivo/iQOO battery/security controls:
   - Battery / 电池 -> Background power consumption / 后台耗电管理 -> `Rokid Photo AI` -> allow unrestricted or high background power usage.
   - i管家 / 安全 / 权限管理 -> 自启动 -> allow `Rokid Photo AI`.
   - App details -> Battery -> Unrestricted / 不受限制, if the option exists.
4. Keep the persistent `Rokid Photo AI` notification visible while the glasses bridge is needed.
5. On vivo/iQOO, lock the app in Recent Tasks as an extra guard during long tests.

ADB can confirm the standard Android side:

```powershell
adb -s 10AF1F0PST00419 shell dumpsys activity services com.example.rokidphone
adb -s 10AF1F0PST00419 shell dumpsys power
```

Look for `PhoneAIService` as a foreground service with type `connectedDevice` and a `PARTIAL_WAKE_LOCK` named `com.example.rokidphone:GlassesBridge`. If the wake lock shows `DISABLED` or `mIsFrozen` after the screen turns off, finish the vivo/iQOO manual battery and auto-start settings above.

Default prompt:

```text
帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

## Knowledge Base Assets

The phone app ships compact text snapshots in `phone-app/src/main/assets/knowledge_bases/`.
The UI persists the selected knowledge base and injects selected snippets into both the phone-camera test path and the glasses-photo analysis path.
The default `Auto` mode scores all available knowledge bases for each request. If the prompt clearly matches one domain, it injects that knowledge base; if the prompt is too generic, it mixes a few high-priority snippets from both Software Engineering and French TCF/TEF so the vision model can ignore irrelevant context after reading the image.

Manual modes are still available: `Software Engineering`, `French TCF/TEF`, and `None`.

Default source folders used by the sync script:

```text
Software Engineering: C:\Users\Apricity\Desktop\软件工程课程小作业
French TCF/TEF:       F:\tcftef
```

Create the project-local Python environment and regenerate the assets:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts\requirements-knowledge.txt
.\.venv\Scripts\python.exe scripts\sync_knowledge_bases.py
```

You can override either source path:

```powershell
.\.venv\Scripts\python.exe scripts\sync_knowledge_bases.py `
  --software-root "C:\Users\Apricity\Desktop\软件工程课程小作业" `
  --french-root "F:\tcftef"
```

The generated assets contain excerpts from local study materials. Review them before pushing to a public repository.

## Glasses App

Main functions:

- Select and connect to the paired phone over Bluetooth SPP.
- Capture a photo from the glasses camera.
- Compress to the shared photo profile.
- Send `PHOTO_START`, `PHOTO_DATA`, and `PHOTO_END` packets.
- Receive AI status/result messages and show them on the glasses UI.

The current sender path is best-effort for normal transfers. The phone can emit ACK/RETRY packets, but full sender-side retransmission is still a robustness item to finish before field use.

## AI Relay Config

The phone relay client reads values from `local.properties`, with debug defaults in `phone-app/build.gradle.kts`. It tries the primary relay first, then falls back to the secondary relay when the primary request fails.

```properties
CODEX_RELAY_URL=https://api.20021004.xyz
CODEX_RELAY_API_KEY=sk-your-codex-relay-key
CODEX_RELAY_MODEL=gpt-5.5
CODEX_RELAY_FALLBACK_URL=https://api.aicodemirror.com/api/codex/backend-api/codex
CODEX_RELAY_FALLBACK_API_KEY=sk-your-fallback-codex-relay-key
CODEX_RELAY_FALLBACK_MODEL=gpt-5.5
DEFAULT_VISION_PROMPT=帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

The request uses:

- Endpoint: `POST {configured relay URL}/v1/responses`
- Authorization: `Bearer {configured relay API key}`
- Input content: `input_text` prompt plus `input_image` data URL
- Tunable fields: `reasoning.effort`, `text.verbosity`, `max_output_tokens`

## Photo Transfer Profile

Shared defaults live in `common/src/main/java/com/example/rokidcommon/protocol/photo/PhotoTransferConstants.kt`.

```text
Target image: 1280x720 max fit
JPEG quality: 70
Max compressed size target: 200 KB
Chunk size: 4096 bytes
Transfer timeout: 60 s
ACK timeout: 5 s
```

The phone camera test uses the same target dimensions, JPEG quality, max size, and chunk estimate to approximate the glasses path before sending to AI.

## Build

On Windows with Android Studio JBR:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\yibo\AppData\Local\Android\Sdk'
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat --dependency-verification=off :common:testDebugUnitTest :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest :phone-app:assembleDebug :glasses-app:assembleDebug
```

APK outputs:

```text
phone-app/build/outputs/apk/debug/phone-app-debug.apk
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

Install the phone app only to the intended phone device, for example:

```powershell
adb -s 10AF1F0PST00419 install -r -g phone-app\build\outputs\apk\debug\phone-app-debug.apk
adb -s 10AF1F0PST00419 shell monkey -p com.example.rokidphone 1
```

If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone already has the same package installed with a different signing key. Uninstall the existing package first only if it is OK to lose that app's local data, or rebuild with the original signing key.

For a non-destructive UI check, you can temporarily add this to `local.properties` and rebuild. It installs beside the existing app instead of replacing it:

```properties
PHONE_DEBUG_APPLICATION_ID_SUFFIX=.kbtest
PHONE_DEBUG_VERSION_NAME_SUFFIX=-kbtest
```

## iQOO/vivo Background Setup

Android foreground service, wake lock, and the normal battery optimization whitelist are all enabled in the phone app. On iQOO/vivo, the vendor process freezer can still freeze a foreground-service app unless the app is allowed in the vendor background power settings.

For repeatable ADB test setup, run:

```powershell
.\scripts\configure-iqoo-background.ps1 -PhoneSerial 10AF1F0PST00419 -StartApp -WakeScreen
```

The script applies the standard ADB allowances, moves the app to the active standby bucket, and runs a sticky `cmd activity unfreeze` for the current process. If `dumpsys activity processes com.example.rokidphone` still reports `isFrozen=true`, use the phone app's `Background Power Settings` button and enable Auto-start plus unrestricted background power for Rokid Photo AI.

## Manual Test Checklist

1. Phone-only AI path
   - Launch phone app.
   - Confirm URL/model/prompt and the knowledge-base selector are shown.
   - Pick `Software Engineering`, `French TCF/TEF`, or `None`.
   - Set `Fast` preset first: reasoning `minimal`, timeout `60s`.
   - Tap `Take Phone Photo and Test AI`.
   - Verify the status changes from reading photo, to glasses simulation, to knowledge-base context, to calling AI, then result or timeout.

2. Timeout/quality path
   - For `medium` or `high` reasoning, raise timeout to `60-120s`.
   - Check logcat for `AI request start` and `AI request done`.
   - Compare `uploadBytes`, `httpMs`, and `totalMs`.

3. Glasses integration path
   - Pair phone and glasses over Bluetooth.
   - Start the phone bridge service.
   - Connect glasses to the phone.
   - Trigger capture on glasses or press `Ask Glasses to Capture and Analyze` on phone.
   - Verify photo transfer progress, selected knowledge-base status, AI status, and result display.

Useful logcat filters:

```powershell
adb -s 10AF1F0PST00419 logcat -d -v time -s MainActivity CodexRelayVisionClient PhoneAIService BluetoothSppManager BluetoothPhotoReceiver
```

## Runtime Notes

- The phone activity is locked to portrait.
- The phone bridge runs as a foreground `connectedDevice` service with a `PARTIAL_WAKE_LOCK` so Bluetooth SPP can keep running while the phone is in the background or the screen is off.
- On Android, grant Bluetooth permissions and allow notification permission when possible. Notification permission is not treated as a hard gate for starting the bridge, but a visible foreground notification makes the background service easier to monitor.
- Disable battery optimization for the phone app from the in-app `Allow Background` action. On iQOO/vivo, also use `Background Power Settings` and enable auto-start plus unrestricted background power/background running for Rokid Photo AI.
- If ADB only shows another device or `offline`, reconnect the iQOO and confirm USB debugging authorization before reading logs or installing.
- Installs should be explicit and targeted; avoid installing to unrelated connected devices.
