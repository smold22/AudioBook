package com.yourapp.audiobook.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yourapp.audiobook.AudioBookApplication

class PlaybackService : MediaSessionService() {

    private val controller
        get() = (application as AudioBookApplication).playerController

    override fun onCreate() {
        super.onCreate()
        addSession(controller.mediaSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        controller.mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.saveProgressNow()
        val player = controller.player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        controller.saveProgressNow()
        super.onDestroy()
    }
}