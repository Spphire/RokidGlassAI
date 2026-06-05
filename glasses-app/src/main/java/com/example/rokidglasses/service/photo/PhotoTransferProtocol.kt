package com.example.rokidglasses.service.photo

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.rokidcommon.protocol.photo.AckPacketData
import com.example.rokidcommon.protocol.photo.PacketUtils
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.example.rokidcommon.protocol.photo.PhotoTransferState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream

sealed class PhotoTransferResponse {
    data class Ack(val data: AckPacketData) : PhotoTransferResponse()
    data class Retry(val chunkIndex: Int) : PhotoTransferResponse()
}

/**
 * Photo Transfer Protocol - Glasses Side (Sender)
 * 
 * Responsible for chunked photo transfer from Rokid Glasses to Android Phone.
 * Implements reliable transfer with CRC32 verification and retry mechanism.
 * 
 * Usage:
 * ```
 * val protocol = PhotoTransferProtocol(socket) { current, total ->
 *     Log.d("Progress", "$current / $total")
 * }
 * 
 * val result = protocol.sendPhoto(imageData)
 * result.onSuccess { Log.d("Transfer", "Success") }
 * result.onFailure { Log.e("Transfer", "Failed", it) }
 * ```
 */
class PhotoTransferProtocol(
    private val bluetoothSocket: BluetoothSocket,
    private val onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    private val receiveResponse: (suspend () -> PhotoTransferResponse)? = null,
    private val clearPendingResponses: (() -> Unit)? = null,
    private val writePacketOverride: (suspend (ByteArray) -> Unit)? = null,
    private val responseTimeoutMs: Long = PhotoTransferConstants.ACK_TIMEOUT_MS
) {
    companion object {
        private const val TAG = "PhotoTransferProtocol"
    }
    
    // Transfer state flow
    private val _transferState = MutableStateFlow<PhotoTransferState>(PhotoTransferState.Idle)
    val transferState: StateFlow<PhotoTransferState> = _transferState.asStateFlow()
    
    // Statistics
    private var transferStartTime: Long = 0
    private var totalBytesSent: Long = 0
    private var retryCount: Int = 0
    
    // I/O streams
    private val outputStream: OutputStream?
        get() = bluetoothSocket.outputStream
    
    private val reliableMode: Boolean
        get() = receiveResponse != null
    
    /**
     * Send photo data to the connected phone.
     * 
     * This method:
     * 1. Calculates MD5 hash of the entire photo
     * 2. Splits the photo into chunks
     * 3. Sends START packet with metadata
     * 4. Sends each DATA packet with CRC32 verification
     * 5. Handles ACK/RETRY responses from receiver
     * 6. Sends END packet to mark completion
     * 
     * @param imageData The compressed JPEG image data
     * @return Result indicating success or failure with error details
     */
    suspend fun sendPhoto(imageData: ByteArray): Result<TransferStatistics> = withContext(Dispatchers.IO) {
        try {
            // Validate socket connection
            if (!bluetoothSocket.isConnected) {
                return@withContext failTransfer(IOException("Bluetooth socket not connected"))
            }

            if (imageData.isEmpty()) {
                return@withContext failTransfer(IllegalArgumentException("Photo data is empty"))
            }
            
            // Reset statistics
            transferStartTime = System.currentTimeMillis()
            totalBytesSent = 0
            retryCount = 0
            
            // Calculate metadata
            val md5 = PacketUtils.calculateMD5(imageData)
            val chunks = PacketUtils.splitIntoChunks(imageData)
            val totalChunks = chunks.size
            clearPendingResponses?.invoke()
            
            Log.d(TAG, "Starting photo transfer: ${imageData.size} bytes, $totalChunks chunks, MD5=${PacketUtils.md5ToHexString(md5)}")
            
            // Update state
            _transferState.value = PhotoTransferState.InProgress(0, totalChunks, 0, imageData.size.toLong())
            
            // Step 1: Send START packet
            val startResult = sendStartPacketWithRetry(imageData.size, totalChunks, md5)
            if (startResult.isFailure) {
                return@withContext failTransfer(startResult.exceptionOrNull()!!)
            }
            
            // Step 2: Send DATA packets
            for ((index, chunk) in chunks.withIndex()) {
                val dataResult = sendDataPacketWithRetry(index, chunk, totalChunks)
                if (dataResult.isFailure) {
                    // Send failure END packet
                    sendEndPacket(PhotoTransferConstants.STATUS_ERROR)
                    return@withContext failTransfer(dataResult.exceptionOrNull()!!)
                }
                
                // Update progress
                totalBytesSent += chunk.size
                _transferState.value = PhotoTransferState.InProgress(
                    index + 1, 
                    totalChunks, 
                    totalBytesSent, 
                    imageData.size.toLong()
                )
                onProgress(index + 1, totalChunks)
                
                // Small delay to prevent buffer overflow
                delay(PhotoTransferConstants.CHUNK_DELAY_MS)
            }
            
            // Step 3: Send END packet
            val endResult = sendEndPacket(PhotoTransferConstants.STATUS_SUCCESS)
            if (endResult.isFailure) {
                return@withContext failTransfer(endResult.exceptionOrNull()!!)
            }
            
            // Calculate statistics
            val elapsedMs = System.currentTimeMillis() - transferStartTime
            val transferRate = if (elapsedMs > 0) {
                (imageData.size.toFloat() / elapsedMs) * 1000 / 1024 // KB/s
            } else 0f
            
            val stats = TransferStatistics(
                totalBytes = imageData.size,
                totalChunks = totalChunks,
                elapsedTimeMs = elapsedMs,
                transferRateKBps = transferRate,
                retryCount = retryCount
            )
            
            Log.d(TAG, "Transfer completed: $stats")
            _transferState.value = PhotoTransferState.Success(imageData)
            
            Result.success(stats)
            
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            _transferState.value = PhotoTransferState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun <T> failTransfer(error: Throwable): Result<T> {
        _transferState.value = PhotoTransferState.Error(error.message ?: "Unknown error")
        return Result.failure(error)
    }
    
    /**
     * Send START packet to initiate transfer.
     */
    private suspend fun sendStartPacketWithRetry(totalSize: Int, totalChunks: Int, md5: ByteArray): Result<Unit> {
        val maxAttempts = if (reliableMode) PhotoTransferConstants.MAX_RETRY_COUNT + 1 else 1
        var attempts = 0

        while (attempts < maxAttempts) {
            attempts++

            val result = sendStartPacket(totalSize, totalChunks, md5)
            if (result.isFailure) {
                retryCount++
                delay(100)
                continue
            }

            if (!reliableMode) {
                return Result.success(Unit)
            }

            when (val response = waitForExpectedResponse(expectedChunkIndex = 0, label = "START")) {
                is PhotoTransferResponse.Ack -> {
                    if (response.data.isSuccess) {
                        return Result.success(Unit)
                    }
                    Log.w(TAG, "START ACK returned ${PacketUtils.getStatusName(response.data.status)}")
                }
                is PhotoTransferResponse.Retry -> {
                    Log.w(TAG, "START retry requested")
                }
                null -> {
                    Log.w(TAG, "START ACK timeout")
                }
            }

            retryCount++
            delay(100)
        }

        return Result.failure(IOException("Failed to start photo transfer after $maxAttempts attempts"))
    }

    private suspend fun sendStartPacket(totalSize: Int, totalChunks: Int, md5: ByteArray): Result<Unit> {
        return try {
            val packet = PacketUtils.createStartPacket(totalSize, totalChunks, md5)
            writePacketBytes(packet)
            
            Log.d(TAG, "Sent START packet: size=$totalSize, chunks=$totalChunks")
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send START packet", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send DATA packet with retry mechanism.
     * Will retry up to MAX_RETRY_COUNT times if CRC verification fails on receiver side.
     */
    private suspend fun sendDataPacketWithRetry(
        chunkIndex: Int, 
        data: ByteArray,
        totalChunks: Int
    ): Result<Unit> {
        val maxAttempts = if (reliableMode) PhotoTransferConstants.MAX_RETRY_COUNT + 1 else 1
        var attempts = 0
        
        while (attempts < maxAttempts) {
            attempts++
            
            val result = sendDataPacket(chunkIndex, data)
            if (result.isFailure) {
                Log.w(TAG, "Failed to send chunk $chunkIndex, attempt $attempts")
                retryCount++
                delay(100) // Brief delay before retry
                continue
            }

            if (!reliableMode) {
                return Result.success(Unit)
            }

            when (val response = waitForExpectedResponse(chunkIndex, label = "DATA")) {
                is PhotoTransferResponse.Ack -> {
                    if (response.data.isSuccess) {
                        return Result.success(Unit)
                    }
                    Log.w(
                        TAG,
                        "Chunk $chunkIndex ACK returned ${PacketUtils.getStatusName(response.data.status)}, attempt $attempts"
                    )
                }
                is PhotoTransferResponse.Retry -> {
                    Log.w(TAG, "Chunk $chunkIndex retry requested, attempt $attempts")
                }
                null -> {
                    Log.w(TAG, "Chunk $chunkIndex ACK timeout, attempt $attempts")
                }
            }

            retryCount++
            delay(100)
        }
        
        return Result.failure(IOException("Failed to send chunk $chunkIndex after $maxAttempts attempts"))
    }
    
    /**
     * Send a single DATA packet.
     */
    private suspend fun sendDataPacket(chunkIndex: Int, data: ByteArray): Result<Unit> {
        return try {
            val packet = PacketUtils.createDataPacket(chunkIndex, data)
            writePacketBytes(packet)
            
            Log.v(TAG, "Sent DATA packet: chunk=$chunkIndex, size=${data.size}")
            
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send DATA packet $chunkIndex", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send END packet to mark transfer completion.
     */
    private suspend fun sendEndPacket(status: Byte): Result<Unit> {
        return try {
            val packet = PacketUtils.createEndPacket(status)
            writePacketBytes(packet)
            
            Log.d(TAG, "Sent END packet: status=${PacketUtils.getStatusName(status)}")
            
            Result.success(Unit)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send END packet", e)
            Result.failure(e)
        }
    }

    private suspend fun writePacketBytes(packet: ByteArray) {
        val writer = writePacketOverride
        if (writer != null) {
            writer(packet)
            return
        }

        outputStream?.write(packet)
        outputStream?.flush()
    }
    
    private suspend fun waitForExpectedResponse(
        expectedChunkIndex: Int,
        label: String
    ): PhotoTransferResponse? {
        val receiver = receiveResponse ?: return null
        val deadlineMs = System.currentTimeMillis() + responseTimeoutMs

        while (true) {
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0) return null

            val response = withTimeoutOrNull(remainingMs) {
                receiver()
            } ?: return null

            when (response) {
                is PhotoTransferResponse.Ack -> {
                    if (response.data.chunkIndex == expectedChunkIndex) {
                        Log.d(TAG, "Received $label ACK: ${response.data}")
                        return response
                    }
                    Log.w(
                        TAG,
                        "Ignoring ACK for wrong chunk: expected=$expectedChunkIndex, got=${response.data.chunkIndex}"
                    )
                }
                is PhotoTransferResponse.Retry -> {
                    if (response.chunkIndex == expectedChunkIndex) {
                        Log.d(TAG, "Received $label RETRY for chunk ${response.chunkIndex}")
                        return response
                    }
                    Log.w(
                        TAG,
                        "Ignoring RETRY for wrong chunk: expected=$expectedChunkIndex, got=${response.chunkIndex}"
                    )
                }
            }
        }
    }
    
    /**
     * Cancel ongoing transfer.
     */
    fun cancelTransfer() {
        Log.d(TAG, "Transfer cancelled")
        _transferState.value = PhotoTransferState.Error("Transfer cancelled by user")
    }
    
    /**
     * Reset transfer state to Idle.
     */
    fun reset() {
        _transferState.value = PhotoTransferState.Idle
        totalBytesSent = 0
        retryCount = 0
    }
}

/**
 * Statistics for a completed transfer.
 */
data class TransferStatistics(
    val totalBytes: Int,
    val totalChunks: Int,
    val elapsedTimeMs: Long,
    val transferRateKBps: Float,
    val retryCount: Int
) {
    override fun toString(): String {
        return "TransferStatistics(bytes=$totalBytes, chunks=$totalChunks, " +
               "time=${elapsedTimeMs}ms, rate=${"%.2f".format(transferRateKBps)} KB/s, " +
               "retries=$retryCount)"
    }
}

/**
 * Extension function to create PhotoTransferProtocol from BluetoothSocket.
 */
fun BluetoothSocket.createPhotoTransferProtocol(
    onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
): PhotoTransferProtocol {
    return PhotoTransferProtocol(this, onProgress)
}
