# Rokid Photo AI

這是簡化後的手機 + Rokid 眼鏡拍照 AI 專案。眼鏡負責拍照與顯示，手機負責接收圖片、保存 prompt、呼叫 Codex++ 中轉站，並把結果回傳眼鏡。

主要功能：

- 眼鏡觸發拍照。
- Bluetooth SPP 分包傳圖。
- 手機直接拍照測試 AI 鏈路。
- 手機設定 prompt、reasoning、verbosity、tokens、圖片尺寸、JPEG 品質與 timeout。
- 眼鏡顯示 AI 回答。

不包含：語音輸入、STT、TTS、多 AI provider、聊天記錄或 Room 資料庫。
