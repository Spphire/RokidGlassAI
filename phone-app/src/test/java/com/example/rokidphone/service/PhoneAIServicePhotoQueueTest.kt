package com.example.rokidphone.service

import com.example.rokidphone.service.photo.ReceivedPhoto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAIServicePhotoQueueTest {
    @Test
    fun `photo analysis queue does not cancel in flight photo when a new one arrives`() = runTest {
        val photos = MutableSharedFlow<ReceivedPhoto>(extraBufferCapacity = 2)
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val firstMayComplete = CompletableDeferred<Unit>()
        val job = launchPhotoAnalysisQueue(photos) { photo ->
            val id = photo.data.single().toInt()
            started.add(id)
            if (id == 1) {
                firstMayComplete.await()
            }
            completed.add(id)
        }
        runCurrent()

        photos.emit(photo(id = 1))
        runCurrent()
        photos.emit(photo(id = 2))
        runCurrent()

        assertThat(started).containsExactly(1)
        assertThat(completed).isEmpty()

        firstMayComplete.complete(Unit)
        runCurrent()

        assertThat(started).containsExactly(1, 2).inOrder()
        assertThat(completed).containsExactly(1, 2).inOrder()

        job.cancel()
    }

    private fun photo(id: Int) = ReceivedPhoto(
        data = byteArrayOf(id.toByte()),
        timestamp = id.toLong(),
        transferTimeMs = 1L
    )
}
