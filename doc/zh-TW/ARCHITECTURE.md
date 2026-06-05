# 架構

目前專案只保留拍照 AI 流程：

1. 眼鏡拍照。
2. 眼鏡壓縮圖片並透過 Bluetooth SPP 分包傳給手機。
3. 手機把圖片和預設 prompt 發到 Codex++ 中轉站。
4. 手機把文字結果回傳眼鏡顯示。

模組：

- `common`: 共用協議與照片分包格式。
- `phone-app`: 手機 UI、前景服務、Bluetooth 接收、Codex++ relay client。
- `glasses-app`: 眼鏡 UI、Bluetooth 連線、相機、圖片壓縮與結果顯示。
- `app`: 上游舊版參考碼，不參與 Gradle build。
