package com.example.rokidphone.service

import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneAIServicePhotoStatusTest {
    @Test
    fun `photo transfer success does not overwrite ai result status`() {
        val text = photoTransferStatusText(PhotoTransferState.Success(byteArrayOf(1, 2, 3)))

        assertThat(text).isNull()
    }

    @Test
    fun `photo transfer progress and errors produce user visible status`() {
        assertThat(
            photoTransferStatusText(
                PhotoTransferState.InProgress(
                    currentChunk = 1,
                    totalChunks = 4,
                    bytesTransferred = 1024,
                    totalBytes = 4096
                )
            )
        ).isEqualTo("Receiving photo 25%")

        assertThat(
            photoTransferStatusText(
                PhotoTransferState.Error(
                    message = "Missing chunks: 2",
                    errorCode = PhotoTransferConstants.STATUS_ERROR
                )
            )
        ).isEqualTo("Photo transfer failed: Missing chunks: 2")
    }
}
