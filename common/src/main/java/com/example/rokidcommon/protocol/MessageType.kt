package com.example.rokidcommon.protocol

/**
 * Message types used by the simplified phone <-> glasses photo AI flow.
 */
enum class MessageType(val code: Int) {
    // Connection management
    HANDSHAKE(0x00),
    HANDSHAKE_ACK(0x01),
    HEARTBEAT(0x02),
    HEARTBEAT_ACK(0x03),
    DISCONNECT(0x0F),

    // AI request status
    AI_PROCESSING(0x20),
    AI_ERROR(0x2F),

    // Display control
    DISPLAY_TEXT(0x30),
    DISPLAY_CLEAR(0x31),

    // Photo transfer and analysis
    PHOTO_START(0x40),
    PHOTO_DATA(0x41),
    PHOTO_END(0x42),
    PHOTO_ACK(0x43),
    PHOTO_RETRY(0x44),
    PHOTO_CANCEL(0x45),
    PHOTO_ANALYSIS_RESULT(0x46),
    CAPTURE_PHOTO(0x47);

    companion object {
        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
