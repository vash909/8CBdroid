package net.vash.ft8cbdroid.engineprobe

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Separate-UID, translucent foreground focus fixture. Produces no audio and finishes in 1.5 seconds. */
class PreviewFocusActivity : Activity() {
    private var request: AudioFocusRequest? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }.build()
        request = focus
        val result = manager.requestAudioFocus(focus)
        android.util.Log.i("8CBdroid-PreviewFocus", "Focus fixture request: $result")
        Handler(Looper.getMainLooper()).postDelayed({ finish() },1500)
    }
    override fun onDestroy() {
        request?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        super.onDestroy()
    }
}
