package com.mewsic.app.service

object MusicPlaybackController {
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    fun triggerPlayPause() {
        onPlayPause?.invoke()
    }

    fun triggerNext() {
        onNext?.invoke()
    }

    fun triggerPrev() {
        onPrev?.invoke()
    }

    fun triggerSeekTo(positionMs: Long) {
        onSeekTo?.invoke(positionMs)
    }

    fun triggerStop() {
        onStop?.invoke()
    }
}
