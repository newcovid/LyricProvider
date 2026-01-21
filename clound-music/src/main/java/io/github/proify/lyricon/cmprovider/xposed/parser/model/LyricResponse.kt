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

package io.github.proify.lyricon.cmprovider.xposed.parser.model

import io.github.proify.lyricon.cmprovider.xposed.parser.LyricParser
import kotlinx.serialization.Serializable

@Serializable
data class LyricResponse(
    val lrc: String? = null,
    val lrcTranslateLyric: String? = null,
    val yrc: String? = null,
    val yrcTranslateLyric: String? = null,
    val pureMusic: Boolean = false,
    val musicId: Long = 0,
) {
    fun toLyricInfo(): LyricInfo = LyricParser.toLyricInfo(this)
}