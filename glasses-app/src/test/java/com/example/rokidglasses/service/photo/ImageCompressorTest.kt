package com.example.rokidglasses.service.photo

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImageCompressorTest {

    @Test
    fun `compressForTransfer center crops portrait images for reading`() = runTest {
        val imageData = createJpeg(width = 720, height = 1280)

        val compressed = ImageCompressor.compressForTransfer(imageData)

        assertThat(ImageCompressor.getImageDimensions(compressed)).isEqualTo(720 to 720)
    }

    @Test
    fun `compressForTransfer keeps landscape images uncropped`() = runTest {
        val imageData = createJpeg(width = 1280, height = 720)

        val compressed = ImageCompressor.compressForTransfer(imageData)

        assertThat(ImageCompressor.getImageDimensions(compressed)).isEqualTo(1280 to 720)
    }

    private fun createJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
