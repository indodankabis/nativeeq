package com.nativeeq.equalizer

import android.content.Intent
import android.service.quicksettings.TileService
import java.util.Locale

class EqTileService : TileService() {
    override fun onClick() {
        val nextState = (EqualizerService.currentState + 1) % 16
        val intent = Intent(this, EqualizerService::class.java).apply {
            action = EqualizerService.ACTION_SET_STATE
            putExtra(EqualizerService.EXTRA_STATE, nextState)
        }
        startForegroundService(intent)
        updateTileState(nextState)
    }

    override fun onStartListening() {
        updateTileState(EqualizerService.currentState)
    }

    private fun updateTileState(state: Int) {
        val qs = qsTile ?: return
        val name = EqualizerService.profileNames.getOrNull(state) ?: "Preset $state"
        qs.state = android.service.quicksettings.Tile.STATE_ACTIVE
        qs.label = "EQ: $name"
        qs.subtitle = "0x" + Integer.toHexString(state).uppercase(Locale.ROOT)
        qs.updateTile()
    }
}
