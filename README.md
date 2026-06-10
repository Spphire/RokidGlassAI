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
- Glasses display processing status, errors, and paged AI results.

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
- Tune request parameters: reasoning effort, verbosity, output tokens, upload image side, JPEG quality, and timeout.
- Run `Take Phone Photo and Test AI`, which compresses the phone image like a glasses image before calling AI.
- Run a foreground Bluetooth bridge service so the phone can keep receiving photos in the background.
- Ask connected glasses to capture and analyze a new photo.

Default prompt:

```text
帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

## Glasses App

Main functions:

- Select and connect to the paired phone over Bluetooth SPP.
- Capture a photo from the glasses camera.
- Compress to the shared photo profile.
- Send `PHOTO_START`, `PHOTO_DATA`, and `PHOTO_END` packets.
- Receive AI status/result messages and show them on the glasses UI.

The current sender path is best-effort for normal transfers. The phone can emit ACK/RETRY packets, but full sender-side retransmission is still a robustness item to finish before field use.

## AI Relay Config

The phone relay client reads values from `local.properties`, with debug defaults in `phone-app/build.gradle.kts`.

```properties
CODEX_RELAY_URL=https://api.20021004.xyz
CODEX_RELAY_API_KEY=sk-your-codex-relay-key
CODEX_RELAY_MODEL=gpt-5.5
DEFAULT_VISION_PROMPT=帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

The request uses:

- Endpoint: `POST {CODEX_RELAY_URL}/v1/responses`
- Authorization: `Bearer {CODEX_RELAY_API_KEY}`
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

## Manual Test Checklist

1. Phone-only AI path
   - Launch phone app.
   - Confirm URL/model/prompt are shown.
   - Set `Fast` preset first: reasoning `minimal`, timeout `25s`.
   - Tap `Take Phone Photo and Test AI`.
   - Verify the status changes from reading photo, to glasses simulation, to calling AI, then result or timeout.

2. Timeout/quality path
   - For `medium` or `high` reasoning, raise timeout to `60-120s`.
   - Check logcat for `AI request start` and `AI request done`.
   - Compare `uploadBytes`, `httpMs`, and `totalMs`.

3. Glasses integration path
   - Pair phone and glasses over Bluetooth.
   - Start the phone bridge service.
   - Connect glasses to the phone.
   - Trigger capture on glasses or press `Ask Glasses to Capture and Analyze` on phone.
   - Verify photo transfer progress, AI status, and result display.

Useful logcat filters:

```powershell
adb -s 10AF1F0PST00419 logcat -d -v time -s MainActivity CodexRelayVisionClient PhoneAIService BluetoothSppManager BluetoothPhotoReceiver
```

## Runtime Notes

- The phone activity is locked to portrait.
- The phone bridge runs as a foreground service and requests battery optimization exemption.
- If ADB only shows another device or `offline`, reconnect the iQOO and confirm USB debugging authorization before reading logs or installing.
- Installs should be explicit and targeted; avoid installing to unrelated connected devices.
