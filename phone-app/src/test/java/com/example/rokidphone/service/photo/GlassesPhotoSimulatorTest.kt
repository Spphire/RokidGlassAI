package com.example.rokidphone.service.photo

import android.graphics.Bitmap
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GlassesPhotoSimulatorTest {
    @Test
    fun `simulation profile matches photo transfer constants`() {
        val profile = GlassesPhotoSimulator.profile

        assertThat(profile.targetWidth).isEqualTo(PhotoTransferConstants.TARGET_IMAGE_WIDTH)
        assertThat(profile.targetHeight).isEqualTo(PhotoTransferConstants.TARGET_IMAGE_HEIGHT)
        assertThat(profile.jpegQuality).isEqualTo(PhotoTransferConstants.JPEG_QUALITY)
        assertThat(profile.maxCompressedSize).isEqualTo(PhotoTransferConstants.MAX_COMPRESSED_SIZE)
        assertThat(profile.chunkSize).isEqualTo(PhotoTransferConstants.CHUNK_SIZE)
    }

    @Test
    fun `simulate compresses to glasses transfer envelope`() = runTest {
        val original = createJpeg(width = 2400, height = 1600, quality = 95)

        val simulated = GlassesPhotoSimulator.simulate(original)

        assertThat(simulated.originalBytes).isEqualTo(original.size)
        assertThat(simulated.compressedBytes).isEqualTo(simulated.data.size)
        assertThat(simulated.width).isAtMost(PhotoTransferConstants.TARGET_IMAGE_WIDTH)
        assertThat(simulated.height).isAtMost(PhotoTransferConstants.TARGET_IMAGE_HEIGHT)
        assertThat(simulated.quality).isAtMost(PhotoTransferConstants.JPEG_QUALITY)
        assertThat(simulated.chunks).isEqualTo(
            GlassesPhotoSimulator.estimateChunkCount(simulated.compressedBytes)
        )
        assertThat(simulated.estimatedBluetoothMs).isEqualTo(
            GlassesPhotoSimulator.estimateBluetoothTransferMs(simulated.compressedBytes)
        )
        assertThat(simulated.summary(simulateMs = 12)).contains("Glass sim:")
        assertThat(simulated.summary(simulateMs = 12)).contains("JPEG q")
    }

    @Test
    fun `invalid image falls back to original bytes`() = runTest {
        val invalid = byteArrayOf(1, 2, 3, 4, 5)

        val simulated = GlassesPhotoSimulator.simulate(invalid)

        assertThat(simulated.data.asList()).isEqualTo(invalid.asList())
        assertThat(simulated.originalBytes).isEqualTo(invalid.size)
        assertThat(simulated.compressedBytes).isEqualTo(invalid.size)
        assertThat(simulated.width).isEqualTo(0)
        assertThat(simulated.height).isEqualTo(0)
        assertThat(simulated.quality).isEqualTo(100)
    }

    @Test
    fun `sample size and estimates match protocol math`() {
        assertThat(GlassesPhotoSimulator.calculateInSampleSize(2400, 1600)).isEqualTo(1)
        assertThat(GlassesPhotoSimulator.calculateInSampleSize(4800, 3200)).isEqualTo(2)
        assertThat(GlassesPhotoSimulator.estimateChunkCount(1)).isEqualTo(1)
        assertThat(GlassesPhotoSimulator.estimateChunkCount(PhotoTransferConstants.CHUNK_SIZE + 1))
            .isEqualTo(2)
        assertThat(GlassesPhotoSimulator.estimateBluetoothTransferMs(50_000)).isEqualTo(1_000)
    }

    private fun createJpeg(width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
