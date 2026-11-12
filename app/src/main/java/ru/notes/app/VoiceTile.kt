package ru.notes.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Плитка в шторке: нажал — надиктовал заметку. */
class VoiceTile : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val i = Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
