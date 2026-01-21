/*
 * Copyright 2026 Proify, Tomakino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.provider.common.extensions.deflate
import io.github.proify.lyricon.provider.common.extensions.inflate
import io.github.proify.lyricon.provider.common.extensions.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.Locale

/**
 * 歌词磁盘缓存管理器。
 */
object DiskSongCache {
    private var baseDir: File? = null

    fun initialize(context: Context) {
        val lyriconDir = File(context.externalCacheDir, "lyricon")
        val locale = Locale.getDefault()
        baseDir = File(File(lyriconDir, "songs"), locale.toLanguageTag()).apply { mkdirs() }
        Log.d("DiskSongCache", "baseDir=$baseDir")
    }

    /**
     * 保存歌曲缓存。
     */
    @Synchronized
    fun save(diskSong: DiskSong): Boolean {
        val song = diskSong.song
        val id = song?.id
        if (id.isNullOrBlank()) {
            YLog.warn("保存歌曲缓存失败，歌曲 ID 为空")
            return false
        }

        return runCatching {
            val string = json.encodeToString(diskSong)
            getFile(id).apply {
                parentFile?.mkdirs()
                writeBytes(string.toByteArray(Charsets.UTF_8).deflate())
            }
            true
        }.getOrElse {
            YLog.debug(baseDir)
            YLog.error(e = it)
            false
        }
    }

    /**
     * 加载歌曲缓存。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun load(id: String): DiskSong? {
        val file = getFile(id)
        if (!file.exists()) return null

        return runCatching {
            file.readBytes()
                .inflate()
                .let { json.decodeFromStream<DiskSong>(it.inputStream()) }
        }.getOrElse {
            YLog.error(tag = "DiskSongCache", msg = "load error", e = it)
            null
        }
    }

    private fun getFile(id: String): File = File(baseDir, "$id.json.gz")
}