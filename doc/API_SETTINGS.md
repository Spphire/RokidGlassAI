# API Settings

This project uses a single Codex++ relay configuration in the phone app.

## local.properties

```properties
CODEX_RELAY_URL=http://8.209.234.8:8080
CODEX_RELAY_API_KEY=sk-your-codex-relay-key
CODEX_RELAY_MODEL=gpt-5.5
DEFAULT_VISION_PROMPT=帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

Debug defaults are defined in `phone-app/build.gradle.kts`, then exposed through `BuildConfig` and `CodexRelayConfig`.

## Request Shape

`CodexRelayVisionClient` sends:

- `POST {CODEX_RELAY_URL}/v1/responses`
- `Authorization: Bearer {CODEX_RELAY_API_KEY}`
- `model: CODEX_RELAY_MODEL`
- `input_text`: the saved prompt
- `input_image`: JPEG as a data URL
- `reasoning.effort`, `text.verbosity`, and `max_output_tokens` from the phone UI

## Recommended Runtime Presets

```text
Fast chain test: reasoning=minimal, timeout=25s
Balanced: reasoning=low/medium, timeout=60-75s
Quality: reasoning=high, timeout=90-120s
```

Timeouts around 25 seconds are expected to fail for slower reasoning settings when the relay/model response is slow.
