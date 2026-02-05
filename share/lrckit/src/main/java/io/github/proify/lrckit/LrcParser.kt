/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricLine

/**
 * LRC 歌词解析器。
 *
 * 用于解析标准及非标准 LRC 歌词文本，支持多时间标签、小时字段及高精度毫秒。
 *
 * ### 支持的格式示例
 * - **标准格式**: `[00:12.34]` (百分秒)
 * - **扩展小时格式**: `[01:02:03.45]` (支持超过一小时的音频)
 * - **高精度格式**: `[00:12.345]` (毫秒级别)
 * - **非标兼容**: `[00:12:34]` (冒号分隔小数位) 或 `[00:12]` (无小数)
 * - **溢出分钟**: `[120:00.00]` (支持超过 99 分钟)
 */
object LrcParser {

    /**
     * 时间标签正则表达式。
     *
     * 捕获组说明：
     * 1. `groupValues[1]`: 小数部分前的 **小时** (可选)。
     * 2. `groupValues[2]`: **分钟** (必须)。
     * 3. `groupValues[3]`: **秒数** (必须)。
     * 4. `groupValues[4]`: **小数部分** (可选，匹配 1-3 位数字)。
     *
     * 匹配逻辑：
     * `\[(?:(\d+):)?(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]`
     */
    private val TIME_TAG_REGEX = Regex("""\[(?:(\d+):)?(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 元数据标签正则表达式 (例如: `[ti:歌名]`, `[ar:歌手]`)。
     *
     * 捕获组说明：
     * 1. `key`: 标签名称。
     * 2. `value`: 标签内容。
     */
    private val META_TAG_REGEX = Regex("""\[(\w+)\s*:\s*([^]]*)]""")

    /**
     * 解析 LRC 原始字符串。
     *
     * @param raw 原始歌词文本字符串。
     * @param duration 音频总时长（毫秒），若提供则用于修正最后一行歌词的结束时间。
     * @return 返回包含元数据和已排序歌词行列表的 [LrcDocument]。
     */
    fun parse(raw: String?, duration: Long = 0): LrcDocument {
        if (raw.isNullOrBlank()) return LrcDocument(emptyMap(), emptyList())

        val tempEntries = mutableListOf<LyricLine>()
        val metaData = mutableMapOf<String, String>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            // 快速跳过无效行
            if (trimmed.isBlank() || !trimmed.startsWith("[")) return@forEach

            val timeMatches = TIME_TAG_REGEX.findAll(trimmed).toList()

            if (timeMatches.isNotEmpty()) {
                // 歌词内容通常位于最后一个时间标签之后
                val lastMatch = timeMatches.last()
                val content = trimmed.substring(lastMatch.range.last + 1).trim()

                timeMatches.forEach { match ->
                    val ms = parseTimeToMs(
                        hour = match.groupValues[1],
                        min = match.groupValues[2],
                        sec = match.groupValues[3],
                        frac = match.groupValues.getOrNull(4)
                    )
                    tempEntries.add(LyricLine(begin = ms, text = content))
                }
            } else {
                // 若非时间标签，则尝试解析为元数据（如 [ar:歌手]）
                META_TAG_REGEX.matchEntire(trimmed)?.let { match ->
                    metaData[match.groupValues[1]] = match.groupValues[2].trim()
                }
            }
        }

        if (tempEntries.isEmpty()) return LrcDocument(metaData, emptyList())

        // 核心逻辑：按时间轴排序并填充结束时间(end)和时长(duration)
        val sortedInitial = tempEntries.sortedBy { it.begin }
        val resultLines = sortedInitial.mapIndexed { index, current ->
            val nextStart = if (index + 1 < sortedInitial.size) {
                sortedInitial[index + 1].begin
            } else {
                null
            }

            // 策略：若无下一行，优先使用总时长，否则默认展示 5 秒
            val end = nextStart ?: if (duration > 0) duration else current.begin + 5000L

            current.copy(
                end = end,
                duration = end - current.begin
            )
        }

        return LrcDocument(metaData, resultLines)
    }

    /**
     * 将解析出的时间各分量转换为统一的毫秒数。
     *
     * 小数部分 (frac) 精度自适应处理：
     * - `1位` (如 .5): 视为 500ms (5 * 100)
     * - `2位` (如 .05): 视为 50ms (05 * 10)，标准的 LRC 百分秒格式
     * - `3位` (如 .005): 视为 5ms，高精度格式
     *
     * @param hour 小时字符串 (允许为 null 或空)。
     * @param min 分钟字符串 (非空数字)。
     * @param sec 秒数字符串 (非空数字)。
     * @param frac 小数部分字符串 (如 "34" 代表 340ms 或 34ms，取决于长度)。
     * @return 转换后的总毫秒数 [Long]。
     */
    private fun parseTimeToMs(hour: String?, min: String, sec: String, frac: String?): Long {
        val h = hour?.toLongOrNull() ?: 0L
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L

        val ms = when (frac?.length) {
            null, 0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            3 -> frac.toLong()
            else -> frac.substring(0, 3).toLongOrNull() ?: 0L
        }

        return (h * 3600000L) + (m * 60000L) + (s * 1000L) + ms
    }
}