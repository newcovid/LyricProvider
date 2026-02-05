/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.prefs
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.paprovider.ui.Config
import io.github.proify.lyricon.paprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PowerAmp 歌词提供者 Xposed 钩子对象。
 * 负责监听 PowerAmp 的播放状态、轨道变化，并通过 LyriconProvider 分发歌词数据。
 */
object PowerAmp : YukiBaseHooker() {
    private const val TAG = "PowerAmpProvider"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
    private const val ACTION_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED"

    private var provider: LyriconProvider? = null
    private var currentPlayingState = false

    // 时间同步相关锚点
    private var lastSyncedPosition: Long = 0L
    private var lastUpdateTimeMillis: Long = 0L
    private var playbackRate: Float = 1.0f

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null
    private var receiver: BroadcastReceiver? = null

    /** 用于匹配音频标签中歌词字段的正则 */
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    override fun onHook() {
        onAppLifecycle {
            onCreate { init(this) }
            onTerminate { release() }
        }
        hookMediaSession()
    }

    /**
     * Hook MediaSession 以获取精准的播放进度状态。
     */
    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState ?: return@after
                        updateSyncAnchor(
                            state.position,
                            state.playbackSpeed
                        )
                    }
                }
            }
    }

    /**
     * 初始化提供者和广播接收器。
     *
     * @param context 应用上下文
     */
    private fun init(context: Context) {
        setupLyriconProvider(context)
        setupBroadcastReceiver(context)
    }

    /**
     * 释放资源，取消所有正在运行的任务。
     */
    private fun release() {
        stopSyncPositionTask()
        coroutineScope.cancel()
        receiver?.let { appContext?.unregisterReceiver(it) }
        receiver = null
    }

    private fun setupLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            val isTranslationEnabled = context.prefs().get(Config.ENABLE_TRANSLATION)
            player.setDisplayTranslation(isTranslationEnabled)
            register()
        }
    }

    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_TRACK_CHANGED)
            addAction(ACTION_STATUS_CHANGED)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_TRACK_CHANGED -> handleTrackChange(intent)
                    ACTION_STATUS_CHANGED -> handleStatusChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    /**
     * 开启进度同步任务。
     */
    private fun startSyncPositionTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive) {
                val position = calculateCurrentPosition()
                provider?.player?.setPosition(position)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    /**
     * 停止进度同步任务。
     */
    private fun stopSyncPositionTask() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * 更新位置推算的基准锚点。
     * @param position 媒体当前播放的进度（毫秒）
     * @param rate 当前播放倍率
     */
    private fun updateSyncAnchor(position: Long, rate: Float) {
        lastSyncedPosition = position
        playbackRate = if (rate > 0) rate else 1.0f
        lastUpdateTimeMillis = SystemClock.elapsedRealtime()
    }

    /**
     * 根据锚点和流逝时间推算当前实时位置。
     * @return 推算出的当前播放位置（毫秒）
     */
    private fun calculateCurrentPosition(): Long {
        if (!currentPlayingState) return lastSyncedPosition
        val elapsed = SystemClock.elapsedRealtime() - lastUpdateTimeMillis
        return lastSyncedPosition + (elapsed * playbackRate).toLong()
    }

    private var lastTrackSignature: Any? = null

    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return
        val metadata = TrackMetadataCache.save(bundle) ?: return

        if (lastTrackSignature == metadata) return
        lastTrackSignature = metadata

        val path = metadata.path ?: return
        val resolvePath = resolvePowerampPath(path) ?: return

        val uri = SafUriResolver.resolveToUri(appContext!!, resolvePath) ?: return

        coroutineScope.launch(Dispatchers.IO) {
            setSongFromUri(metadata, uri)
        }
    }

    /**
     * 从指定的 URI 读取并解析歌词，随后更新提供者。
     */
    private fun setSongFromUri(data: TrackMetadata, uri: Uri) {
        val startTime = System.currentTimeMillis()
        val lyric = matchLyric(uri) ?: return run {
            YLog.debug(
                tag = TAG,
                msg = "No lyric found in $uri"
            )
            provider?.player?.setSong(Song(name = data.title, artist = data.artist))
        }

        val lines = EnhanceLrcParser.parse(lyric, data.duration).lines
        val song = Song(
            data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = lines
        )

        provider?.player?.setSong(song)

        YLog.debug(
            tag = TAG,
            msg = "Song updated. Match/Parse cost: ${System.currentTimeMillis() - startTime}ms"
        )
    }

    /**
     * 通过 TagLib 从文件元数据中匹配歌词。
     */
    private fun matchLyric(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        YLog.error(tag = TAG, msg = "Match lyric failed: $uri", e = e)
        null
    }

    /**
     * 解析 Poweramp 相对路径。
     * 例如: `primary/Music/Jay.flac` -> `primary:Music/Jay.flac`
     */
    private fun resolvePowerampPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null

        val firstSlash = trimmed.indexOf('/')
        if (firstSlash == -1) return null

        val volumeId = trimmed.substring(0, firstSlash)
        val relativePath = trimmed.substring(firstSlash + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    private fun handleStatusChange(intent: Intent) {
        val isPlaying = !intent.getBooleanExtra("paused", true)
        if (isPlaying == currentPlayingState) return
        provider?.player?.setPlaybackState(isPlaying)
        currentPlayingState = isPlaying

        if (isPlaying) startSyncPositionTask() else stopSyncPositionTask()
    }
}