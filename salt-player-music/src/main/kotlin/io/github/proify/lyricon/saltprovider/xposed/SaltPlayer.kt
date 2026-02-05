/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import android.app.Notification
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.saltprovider.xposed.SaltPlayer.TIMEOUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object SaltPlayer : YukiBaseHooker() {

    /** 魅族 Ticker 标志位 */
    private const val FLAG_TICKER = 0x1000000 or 0x2000000

    /** 歌词显示的超时时间（毫秒），超时后自动清除歌词 */
    private const val TIMEOUT = 10 * 1000L

    private var isPlaying = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lyricFlow = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    private val provider: LyriconProvider by lazy {
        LyriconFactory.createProvider(
            appContext!!,
            Constants.PROVIDER_PACKAGE_NAME,
            appContext!!.packageName,
            ProviderLogo.fromBase64(Constants.ICON)
        ).apply(LyriconProvider::register)
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                observeLyrics()
                hookMedia()
                hookNotify()
            }
        }
    }

    /**
     * 启动协程观察歌词流。
     * 接收到新歌词时发送至宿主，并在 [TIMEOUT] 时间后若无更新则清除。
     */
    private fun observeLyrics() = scope.launch {
        lyricFlow.collectLatest { text ->
            provider.player.sendText(text)
            if (text != null) {
                delay(TIMEOUT)
                provider.player.sendText(null)
            }
        }
    }

    private fun hookMedia() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = (args[0] as PlaybackState).state
                        when (state) {
                            PlaybackState.STATE_PLAYING -> updatePlaybackStatus(true)
                            PlaybackState.STATE_PAUSED,
                            PlaybackState.STATE_STOPPED -> updatePlaybackStatus(false)

                            else -> Unit
                        }
                    }
                }
            }
    }

    private fun updatePlaybackStatus(state: Boolean) {
        if (isPlaying == state) return
        isPlaying = state
        provider.player.setPlaybackState(state)
    }

    private fun hookNotify() {
        "android.app.NotificationManager".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "notify"
                    parameters(String::class.java, Int::class.java, Notification::class.java)
                }.hook {
                    after {
                        val notify = args[2] as Notification
                        if ((notify.flags and FLAG_TICKER) != 0) {
                            val ticker = notify.tickerText?.toString()
                            lyricFlow.tryEmit(ticker?.trim())
                        }
                    }
                }
            }
    }
}