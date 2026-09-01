package com.vishal.riy.blocker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.devzwy.nsfw.NSFWHelper
import java.util.Collections

/**
 * On-device image classifier (Yahoo OpenNSFW TFLite, bundled as
 * assets/nsfw.tflite via the io.github.devzwy:nsfw library).
 *
 * This is the ONLY layer that actually looks at IMAGE PIXELS, and it can only
 * do so for resources rendered inside THIS app's WebView — Android gives no
 * app access to Chrome's HTTPS-rendered content, and TLS interception is
 * deliberately not used.
 *
 * Scores: [nsfwScore 0..1] where 1.0 = almost certainly pornographic.
 * The decision threshold is deliberately HIGH (see [THRESHOLD]) so
 * swimwear/celebrity/fashion photography ("sexy" but not explicit) is NOT
 * blocked — false positives are worse for the allow-list requirements than
 * missing a borderline suggestive image.
 *
 * Fail-open: if the model or bitmap cannot be loaded, the image is allowed —
 * normal browsing must never break because of the classifier.
 */
object ImageClassifier {

    private const val TAG = "NsfwClassifier"

    /** >= this NSFW score blocks the image. Tuned on-device (see E2E report). */
    internal const val THRESHOLD = 0.60f

    /** Images larger than this are skipped (model input is 224x224 anyway). */
    internal const val MAX_BYTES = 6 * 1024 * 1024

    @Volatile private var initialized = false
    private val initLock = Any()

    /** URL -> decision cache so every image is classified at most once. */
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 300
        },
    )

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                // GPU delegate is disabled: swiftshader emulators and some
                // low-end devices have flaky TFLite GPU support.
                NSFWHelper.initHelper(context.applicationContext, isOpenGPU = false, numThreads = 4)
                initialized = true
                Log.i(TAG, "NSFW classifier ready (threshold=$THRESHOLD)")
            } catch (e: Exception) {
                // Fail-open: classification disabled, URL filter keeps working.
                Log.w(TAG, "classifier init failed (fail-open): ${e.message}")
            }
        }
    }

    /**
     * Classifies raw image bytes. Returns true = NSFW (block), false = safe
     * (or classification unavailable / not an image — fail-open).
     */
    fun classify(cacheKey: String, bytes: ByteArray): Boolean {
        cache[cacheKey]?.let { return it }
        val decision = runCatching { classifyInternal(bytes) }.getOrDefault(false)
        synchronized(cache) { cache[cacheKey] = decision }
        return decision
    }

    internal fun isNsfw(nsfwScore: Float): Boolean = nsfwScore >= THRESHOLD

    private fun classifyInternal(bytes: ByteArray): Boolean {
        if (!initialized || bytes.size > MAX_BYTES || bytes.isEmpty()) return false
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val score = NSFWHelper.getNSFWScore(bitmap)
        bitmap.recycle()
        val blocked = isNsfw(score.nsfwScore)
        Log.i(TAG, "nsfw=${score.nsfwScore} decision=${if (blocked) "BLOCK" else "allow"}")
        return blocked
    }
}
