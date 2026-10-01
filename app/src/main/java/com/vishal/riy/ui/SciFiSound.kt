package com.vishal.riy.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import com.vishal.riy.R

/**
 * Sci-fi cockpit soundboard: short synthesized effects (no streaming, no
 * network). Everything is fire-and-forget and best-effort — audio must never
 * break protection logic or crash the app.
 *
 * Install once from MainActivity (`init`); all `play*` calls are safe before
 * or without it (they simply no-op).
 */
object SciFiSound {

    @Volatile private var pool: SoundPool? = null
    private val loaded = mutableMapOf<Int, Int>()
    private val lock = Any()

    /** Loads the four effects. Idempotent; safe to call repeatedly. */
    fun init(context: Context) {
        if (pool != null) return
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()
            synchronized(lock) {
                if (pool != null) {
                    p.release()
                    return
                }
                pool = p
                loaded[R.raw.sci_fi_blip] = p.load(context.applicationContext, R.raw.sci_fi_blip, 1)
                loaded[R.raw.sci_fi_engage] = p.load(context.applicationContext, R.raw.sci_fi_engage, 1)
                loaded[R.raw.sci_fi_alarm] = p.load(context.applicationContext, R.raw.sci_fi_alarm, 1)
                loaded[R.raw.sci_fi_release] = p.load(context.applicationContext, R.raw.sci_fi_release, 1)
            }
        }
    }

    /** Short laser tap for generic button presses. */
    fun blip() = play(R.raw.sci_fi_blip, volume = 0.6f)

    /** Power-up sweep for "Enable Protection". */
    fun engage() = play(R.raw.sci_fi_engage, volume = 0.9f)

    /** Two-tone klaxon when a restriction engages. */
    fun alarm() = play(R.raw.sci_fi_alarm, volume = 0.9f)

    /** Soft deactivation chime when a restriction ends. */
    fun release() = play(R.raw.sci_fi_release, volume = 0.8f)

    private fun play(@RawRes res: Int, volume: Float) {
        runCatching {
            val p = pool ?: return
            val id = synchronized(lock) { loaded[res] } ?: return
            p.play(id, volume, volume, 1, 0, 1.0f)
        }
    }

    /** Releases the pool (process shutdown only). */
    fun shutdown() {
        runCatching {
            synchronized(lock) {
                pool?.release()
                pool = null
                loaded.clear()
            }
        }
    }
}
