package com.example.rokidphone.service.ai

import android.util.Base64
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

data class AiRequestProgress(
    val stage: AiRequestStage,
    val elapsedMs: Long,
    val detail: String = ""
) {
    fun toStatusText(): String {
        val seconds = elapsedMs / 1_000.0
        val suffix = if (detail.isBlank()) "" else " $detail"
        return "${stage.label} (${String.format("%.1fs", seconds)})$suffix"
    }
}

enum class AiRequestStage(val label: String) {
    OPTIMIZING_IMAGE("Preparing image"),
    ENCODING_IMAGE("Encoding image"),
    BUILDING_REQUEST("Building AI request"),
    WAITING_FOR_RELAY("Waiting for AI relay"),
    PARSING_RESPONSE("Parsing AI response"),
    COMPLETED("AI response received")
}

class CodexRelayVisionClient(
    private val config: VisionRelayConfig = CodexRelayConfig,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(
        imageData: ByteArray,
        prompt: String = config.defaultPrompt,
        requestSettings: AiRequestSettings = AiRequestSettings(),
        onProgress: ((AiRequestProgress) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastProgress = AiRequestProgress(AiRequestStage.OPTIMIZING_IMAGE, 0L)
        try {
            if (imageData.isEmpty()) {
                error("Image data is empty")
            }

            val settings = requestSettings.normalized()
            val totalStartMs = SystemClock.elapsedRealtime()
            fun report(stage: AiRequestStage, detail: String = "") {
                lastProgress = AiRequestProgress(
                    stage = stage,
                    elapsedMs = SystemClock.elapsedRealtime() - totalStartMs,
                    detail = detail
                )
                Log.d(TAG, "AI progress: ${lastProgress.toStatusText()}")
                onProgress?.invoke(lastProgress)
            }

            report(
                AiRequestStage.OPTIMIZING_IMAGE,
                "input=${imageData.size}B, maxSide=${settings.maxImageSidePx}, q=${settings.jpegQuality}"
            )
            val optimizeStartMs = SystemClock.elapsedRealtime()
            val uploadImageData = optimizeImageForRelay(imageData, settings)
            val optimizeMs = SystemClock.elapsedRealtime() - optimizeStartMs

            report(AiRequestStage.ENCODING_IMAGE, "upload=${uploadImageData.size}B")
            val encodeStartMs = SystemClock.elapsedRealtime()
            val imageBase64 = Base64.encodeToString(uploadImageData, Base64.NO_WRAP)
            val encodeMs = SystemClock.elapsedRealtime() - encodeStartMs
            val effectivePrompt = prompt.ifBlank { config.defaultPrompt }

            report(AiRequestStage.BUILDING_REQUEST, "base64=${imageBase64.length} chars")
            val body = JSONObject().apply {
                put("model", config.model)
                put("input", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", effectivePrompt)
                            })
                            put(JSONObject().apply {
                                put("type", "input_image")
                                put("image_url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                    })
                })
                put("max_output_tokens", settings.maxOutputTokens)
                put("reasoning", JSONObject().apply {
                    put("effort", settings.reasoningEffort)
                })
                put("text", JSONObject().apply {
                    put("verbosity", settings.textVerbosity)
                })
            }

            Log.d(
                TAG,
                    "AI request start: originalBytes=${imageData.size}, uploadBytes=${uploadImageData.size}, " +
                    "base64Chars=${imageBase64.length}, optimizeMs=$optimizeMs, encodeMs=$encodeMs, " +
                    "promptChars=${effectivePrompt.length}, maxOutputTokens=${settings.maxOutputTokens}, " +
                    "reasoning=${settings.reasoningEffort}, verbosity=${settings.textVerbosity}, " +
                    "maxImageSide=${settings.maxImageSidePx}, jpegQuality=${settings.jpegQuality}, " +
                    "timeoutSeconds=${settings.timeoutSeconds}"
            )

            val request = Request.Builder()
                .url("${config.baseUrl}/v1/responses")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val httpStartMs = SystemClock.elapsedRealtime()

            val retryDelays = retryDelaysMs.map { it.coerceAtLeast(0L) }
            val maxAttempts = retryDelays.size + 1
            for (attempt in 1..maxAttempts) {
                report(AiRequestStage.WAITING_FOR_RELAY, "timeout=${settings.timeoutSeconds}s attempt=$attempt/$maxAttempts")
                val attemptStartMs = SystemClock.elapsedRealtime()
                val relayResponse = try {
                    client.newCall(request).awaitResponse().use { response ->
                        RelayHttpResponse(
                            code = response.code,
                            isSuccessful = response.isSuccessful,
                            body = response.body?.string().orEmpty(),
                            retryAfterDelayMs = parseRetryAfterDelayMs(response.header("Retry-After"))
                        )
                    }
                } catch (e: IOException) {
                    val attemptMs = SystemClock.elapsedRealtime() - attemptStartMs
                    Log.e(TAG, "Relay network error on attempt $attempt/$maxAttempts after ${attemptMs}ms", e)
                    if (attempt < maxAttempts) {
                        val delayMs = retryDelayMs(
                            attempt = attempt,
                            retryDelays = retryDelays,
                            retryAfterDelayMs = null
                        )
                        report(
                            AiRequestStage.WAITING_FOR_RELAY,
                            "retrying after network error in ${delayMs}ms attempt=$attempt/$maxAttempts"
                        )
                        delay(delayMs)
                        continue
                    }
                    val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    error("AI request failed: network error $message${attemptSuffix(attempt)}")
                }

                val attemptMs = SystemClock.elapsedRealtime() - attemptStartMs
                if (!relayResponse.isSuccessful) {
                    Log.e(
                        TAG,
                        "Relay error ${relayResponse.code} on attempt $attempt/$maxAttempts " +
                            "after ${attemptMs}ms: ${relayResponse.body.take(500)}"
                    )
                    if (isRetryableStatus(relayResponse.code) && attempt < maxAttempts) {
                        val delayMs = retryDelayMs(
                            attempt = attempt,
                            retryDelays = retryDelays,
                            retryAfterDelayMs = relayResponse.retryAfterDelayMs
                        )
                        val retryAfterDetail = relayResponse.retryAfterDelayMs
                            ?.let { ", retry-after=${it}ms" }
                            .orEmpty()
                        report(
                            AiRequestStage.WAITING_FOR_RELAY,
                            "retrying after HTTP ${relayResponse.code} in ${delayMs}ms$retryAfterDetail attempt=$attempt/$maxAttempts"
                        )
                        delay(delayMs)
                        continue
                    }
                    error(httpFailureMessage(relayResponse.code, attempt))
                }

                val httpMs = SystemClock.elapsedRealtime() - httpStartMs
                report(AiRequestStage.PARSING_RESPONSE, "httpMs=${httpMs}ms attempt=$attempt/$maxAttempts")
                val parseStartMs = SystemClock.elapsedRealtime()
                val json = JSONObject(relayResponse.body)
                val content = extractOutputText(json)
                if (content.isBlank()) {
                    Log.w(
                        TAG,
                        "AI response contained no output text: status=${json.optString("status")}, " +
                            "body=${relayResponse.body.take(1_500)}"
                    )
                }
                val parseMs = SystemClock.elapsedRealtime() - parseStartMs
                val totalMs = SystemClock.elapsedRealtime() - totalStartMs

                Log.d(
                    TAG,
                    "AI request done: httpMs=$httpMs, parseMs=$parseMs, totalMs=$totalMs, " +
                        "responseChars=${content.length}"
                )

                report(AiRequestStage.COMPLETED, "total=${totalMs}ms")
                return@withContext Result.success(content.ifBlank { "AI returned an empty response." })
            }

            error("AI request failed after $maxAttempts attempts")
        } catch (e: CancellationException) {
            Log.w(TAG, "AI request cancelled at ${lastProgress.toStatusText()}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI request failed at ${lastProgress.toStatusText()}", e)
            Result.failure(e)
        }
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) {
                    response.close()
                } else {
                    continuation.resume(response)
                }
            }
        })
    }

    private fun optimizeImageForRelay(imageData: ByteArray, settings: AiRequestSettings): ByteArray {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return imageData

        val largestSide = max(width, height)
        if (largestSide <= settings.maxImageSidePx &&
            imageData.size <= AiRequestSettingsStore.DEFAULT_MAX_UPLOAD_IMAGE_BYTES
        ) {
            return imageData
        }

        val sampleSize = calculateSampleSize(width, height, settings.maxImageSidePx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
            ?: return imageData

        val rotated = rotateIfNeeded(decoded, imageData)
        var bestImage: ByteArray? = null
        try {
            for (maxSide in candidateImageSides(settings.maxImageSidePx)) {
                val scaled = scaleToMaxSide(rotated, maxSide)
                try {
                    for (quality in candidateJpegQualities(settings.jpegQuality)) {
                        val compressed = compressJpeg(scaled, quality)
                        if (compressed.isEmpty()) continue
                        if (bestImage == null || compressed.size < bestImage!!.size) {
                            bestImage = compressed
                        }
                        if (compressed.size <= AiRequestSettingsStore.DEFAULT_MAX_UPLOAD_IMAGE_BYTES) {
                            return compressed
                        }
                    }
                } finally {
                    if (scaled !== rotated) scaled.recycle()
                }
            }
        } finally {
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        }

        return bestImage?.takeIf { it.isNotEmpty() && it.size < imageData.size } ?: imageData
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (max(sampledWidth, sampledHeight) / 2 >= maxSide) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun rotateIfNeeded(bitmap: Bitmap, imageData: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(imageData)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maxSide) return bitmap

        val scale = maxSide.toFloat() / largestSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun candidateImageSides(requestedMaxSide: Int): List<Int> {
        val sides = mutableListOf<Int>()
        val minimumSide = minOf(MIN_RELAY_IMAGE_SIDE_PX, requestedMaxSide.coerceAtLeast(1))
        var side = requestedMaxSide.coerceAtLeast(minimumSide)
        while (side >= minimumSide) {
            sides.add(side)
            val nextSide = (side * IMAGE_SIDE_REDUCTION_FACTOR).roundToInt()
            side = if (nextSide >= side) side - 1 else nextSide
        }
        if (sides.lastOrNull() != minimumSide) {
            sides.add(minimumSide)
        }
        return sides.distinct()
    }

    private fun candidateJpegQualities(requestedQuality: Int): List<Int> {
        val qualities = mutableListOf<Int>()
        val minimumQuality = minOf(MIN_RELAY_JPEG_QUALITY, requestedQuality.coerceAtLeast(1))
        var quality = requestedQuality.coerceIn(minimumQuality, 100)
        while (quality >= minimumQuality) {
            qualities.add(quality)
            quality -= JPEG_QUALITY_STEP
        }
        if (qualities.lastOrNull() != minimumQuality) {
            qualities.add(minimumQuality)
        }
        return qualities.distinct()
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    private fun extractOutputText(json: JSONObject): String {
        json.optString("output_text")
            .takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        json.optJSONArray("output")?.let { output ->
            val parts = buildList {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val block = content.optJSONObject(j) ?: continue
                        val text = block.optString("text")
                        if (text.isNotBlank()) {
                            add(text.trim())
                        }
                    }
                }
            }
            parts.joinToString("\n").trim()
                .takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        val choices = json.optJSONArray("choices") ?: return ""
        val choiceParts = buildList {
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                val message = choice.optJSONObject("message")
                val messageContent = message?.opt("content") ?: choice.opt("text")
                when (messageContent) {
                    is String -> {
                        if (messageContent.isNotBlank()) add(messageContent.trim())
                    }
                    is JSONArray -> {
                        for (j in 0 until messageContent.length()) {
                            val block = messageContent.optJSONObject(j) ?: continue
                            val text = block.optString("text")
                            if (text.isNotBlank()) add(text.trim())
                        }
                    }
                }
            }
        }
        return choiceParts.joinToString("\n").trim()
    }

    private fun isRetryableStatus(code: Int): Boolean = code in RETRYABLE_HTTP_STATUS_CODES

    private fun retryDelayMs(
        attempt: Int,
        retryDelays: List<Long>,
        retryAfterDelayMs: Long?
    ): Long {
        val configuredDelayMs = retryDelays
            .getOrElse(attempt - 1) { retryDelays.lastOrNull() ?: 0L }
            .coerceAtLeast(0L)
        return max(configuredDelayMs, retryAfterDelayMs ?: 0L)
            .coerceAtMost(MAX_RETRY_AFTER_DELAY_MS)
    }

    private fun parseRetryAfterDelayMs(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        trimmed.toLongOrNull()?.let { seconds ->
            return seconds
                .coerceIn(0L, MAX_RETRY_AFTER_DELAY_MS / 1_000L)
                .times(1_000L)
        }

        return runCatching {
            val retryAtMs = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (retryAtMs - System.currentTimeMillis())
                .coerceAtLeast(0L)
                .coerceAtMost(MAX_RETRY_AFTER_DELAY_MS)
        }.getOrNull()
    }

    private fun httpFailureMessage(code: Int, attempt: Int): String {
        val suffix = attemptSuffix(attempt)
        return if (isRetryableStatus(code)) {
            "AI relay temporarily failed (HTTP $code)$suffix. Please try again."
        } else {
            "AI request failed: HTTP $code$suffix"
        }
    }

    private fun attemptSuffix(attempt: Int): String =
        if (attempt > 1) " after $attempt attempts" else ""

    private data class RelayHttpResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String,
        val retryAfterDelayMs: Long?
    )

    private companion object {
        private const val TAG = "CodexRelayVisionClient"
        private const val MAX_RETRY_AFTER_DELAY_MS = 30_000L
        private const val MIN_RELAY_IMAGE_SIDE_PX = 960
        private const val MIN_RELAY_JPEG_QUALITY = 58
        private const val JPEG_QUALITY_STEP = 8
        private const val IMAGE_SIDE_REDUCTION_FACTOR = 0.8f
        private val DEFAULT_RETRY_DELAYS_MS = listOf(
            1_000L,
            2_000L,
            4_000L,
            8_000L,
            12_000L,
            20_000L,
            30_000L
        )
        private val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 502, 503, 504)
    }
}
