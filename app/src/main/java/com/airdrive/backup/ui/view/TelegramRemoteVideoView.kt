package com.airdrive.backup.ui.view

import android.content.Context
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.telegram.TelegramRemoteMediaDataSource

class TelegramRemoteVideoView(
    context: Context,
    private val client: TdClient,
    private val chatId: Long,
    private val messageId: Long,
    private val sizeBytes: Long
) : FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingPlay = true

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        prepare(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.setSurface(null)
    }

    private fun prepare(holder: SurfaceHolder) {
        player?.release()
        player = null
        prepared = false
        runCatching {
            MediaPlayer().apply {
                setDataSource(TelegramRemoteMediaDataSource(client, chatId, messageId, sizeBytes))
                setDisplay(holder)
                setOnPreparedListener {
                    prepared = true
                    if (pendingPlay) it.start()
                }
                setOnCompletionListener { pendingPlay = false }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }.also { player = it }
        }
    }

    fun togglePlay() {
        player?.let {
            if (!prepared) {
                pendingPlay = true
            } else if (it.isPlaying) {
                it.pause()
                pendingPlay = false
            } else {
                it.start()
                pendingPlay = true
            }
        }
    }

    fun seekTo(positionMs: Int) {
        player?.takeIf { prepared }?.seekTo(positionMs)
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun duration(): Int = player?.duration ?: 0
    fun position(): Int = player?.currentPosition ?: 0

    override fun onDetachedFromWindow() {
        surfaceView.holder.removeCallback(this)
        player?.release()
        player = null
        super.onDetachedFromWindow()
    }
}
