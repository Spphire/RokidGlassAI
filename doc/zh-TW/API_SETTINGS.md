# API 設定

目前手機端只使用 Codex++ 中轉站。

```properties
CODEX_RELAY_URL=https://api.20021004.xyz
CODEX_RELAY_API_KEY=sk-your-codex-relay-key
CODEX_RELAY_MODEL=gpt-5.5
DEFAULT_VISION_PROMPT=帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释；如果是主观题，分点精简回答去除AI味并以研究生的口吻
```

請在 `local.properties` 覆寫上述值。手機 UI 可調整 reasoning、verbosity、tokens、圖片尺寸、JPEG 品質與 timeout。
