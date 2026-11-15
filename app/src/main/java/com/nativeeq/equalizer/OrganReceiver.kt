package com.nativeeq.equalizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * The organism's throat — the leaf-mesh control surface for NativeEQ.
 *
 *   am broadcast -a com.nativeeq.equalizer.ORGAN --ei state 9
 *   am broadcast -a com.nativeeq.equalizer.ORGAN --efa gains 300,150,0,0,0
 *   am broadcast -a com.nativeeq.equalizer.ORGAN --es bass 800
 *   am broadcast -a com.nativeeq.equalizer.ORGAN --es cmd stop
 *
 * Forwards onto EqualizerService's existing intent API. On A14+ a custom
 * broadcast can't exempt startForegroundService, so we try it and fall
 * back to a plain start (works while the DSP service is resident — the
 * EQ's normal state once armed). Every attempt lands in
 * filesDir/organ_state.json — adb/cat-readable for mesh telemetry.
 */
class OrganReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            onReceiveSafe(context, intent)
        } catch (t: Throwable) {
            // receivers must never crash — the system kills repeat offenders
            try {
                writeState(context, JSONObject()
                    .put("error", t.javaClass.simpleName)
                    .put("ts", System.currentTimeMillis()))
            } catch (_: Exception) {
            }
        }
    }

    private fun onReceiveSafe(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "apply"
        val state = intent.getIntExtra("state", -1)
        val gains = intent.getFloatArrayExtra("gains")
        val svc = Intent(context, EqualizerService::class.java)
        val applied = JSONObject()
        when {
            state in 0 until EqualizerService.profileNames.size -> {
                svc.action = EqualizerService.ACTION_SET_STATE
                svc.putExtra(EqualizerService.EXTRA_STATE, state)
                applied.put("profile", EqualizerService.profileNames[state])
            }
            gains != null && gains.size == 5 -> {
                svc.action = EqualizerService.ACTION_APPLY_NPU_CORRECTION
                svc.putExtra(EqualizerService.EXTRA_NPU_GAINS, gains)
                applied.put("gains", gains.joinToString(","))
            }
            intent.hasExtra("bass") -> {
                svc.action = EqualizerService.ACTION_SET_BASS
                svc.putExtra(EqualizerService.EXTRA_BASS_STRENGTH,
                             intent.getShortExtra("bass", 0))
                applied.put("bass", intent.getShortExtra("bass", 0))
            }
            intent.hasExtra("virt") -> {
                svc.action = EqualizerService.ACTION_SET_VIRT
                svc.putExtra(EqualizerService.EXTRA_VIRT_STRENGTH,
                             intent.getShortExtra("virt", 0))
                applied.put("virt", intent.getShortExtra("virt", 0))
            }
            cmd == "stop" -> {
                context.stopService(Intent(context, EqualizerService::class.java))
                writeState(context, JSONObject().put("stopped", true)
                    .put("ts", System.currentTimeMillis()))
                return
            }
            else -> {
                writeState(context, JSONObject().put("error", "nothing to apply")
                    .put("ts", System.currentTimeMillis()))
                return
            }
        }
        // A14+ custom broadcasts don't exempt startForegroundService — try
        // it, fall back to a plain start (works when the DSP service is
        // already resident — the EQ's normal state once armed), and never
        // let a throw crash a receiver. 'via' records which path spoke.
        var via = "fgs"
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            via = try {
                context.startService(svc)
                "bg"
            } catch (e2: Exception) {
                "asleep:" + e2.javaClass.simpleName
            }
        }
        writeState(context, applied.put("ts", System.currentTimeMillis())
            .put("cmd", cmd).put("state", state).put("via", via))
    }

    private fun writeState(context: Context, o: JSONObject) {
        try {
            File(context.filesDir, "organ_state.json").writeText(o.toString())
        } catch (_: Exception) {
        }
    }
}
