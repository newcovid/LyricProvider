/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

//
//@SuppressLint("StaticFieldLeak")
//object LyricUtil {
//
//    private var context: Context? = null
//
//    private val cloudLyrics by lazy {
//            YLog.debug("正在初始化 CloudLyrics 引擎...")
//        CloudLyrics(
//            listOf(
//                QQMusicProvider(),
//                //LrcLibProvider()
//            )
//        )
//    }
//
//    fun init(ctx: Context) {
//        this.context = ctx
//    }
//
//    private suspend fun searchCloudLyrics(
//        title: String,
//        artist: String,
//        duration: Long
//    ): List<RichLyricLine>? {
//        return try {
//            val engine = cloudLyrics
//
//            val results = engine.search {
//                this.trackName = title
//                this.artistName = artist
//                this.perProviderLimit = 5
//                this.maxTotalResults = 2
//                prefer(score = 50) { _ -> true }
//            }
//
//            if (results.isEmpty()) {
//                YLog.debug("⚪ 云端搜索结束，未找到匹配结果")
//                return null
//            }
//            val bestMatch = results.first()
//            YLog.debug("✅ 命中云端歌词: 源[${bestMatch.provider.id}]")
//            bestMatch.lyrics.rich
//
//        } catch (e: CancellationException) {
//            throw e
//        } catch (t: Throwable) {
//            YLog.error("❌ 云端搜索执行异常", t)
//            null
//        }
//    }
//}