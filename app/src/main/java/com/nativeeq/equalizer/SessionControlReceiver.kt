package com.nativeeq.equalizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Players that support a system EQ announce their real audio session via
 * ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION — no privileged permission
 * needed. Registered at runtime by EqualizerService (catches implicit
 * broadcasts) and in the manifest (catches ones targeted at our package).
 */
class SessionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (session == 0) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                EqualizerService.onControlSession(session, open = true)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                EqualizerService.onControlSession(session, open = false)
        }
    }
}
