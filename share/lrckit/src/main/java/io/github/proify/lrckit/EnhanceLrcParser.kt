/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * Enhance LRC 歌词解析器
 * 支持标准时间轴、逐字时间轴、多角色解析及背景音。
 */
object EnhanceLrcParser {

    private val TIME_TAG_REGEX = Regex("""\[(?:(\d+):)?(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME_TAG_REGEX = Regex("""<(?:(\d+):)?(\d+):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG_REGEX = Regex("""\[(\w+)\s*:\s*([^]]*)]""")
    private val PERSON_REGEX = Regex("""^(v\d+|bg):\s*(.+)$""", RegexOption.IGNORE_CASE)

    private const val MS_PER_SECOND = 1000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L

    /**
     * 解析完整的 LRC 歌词文本
     *
     * @param raw 原始 LRC 文本字符串
     * @param duration 歌曲总时长（毫秒），用于最后一行的时间兜底
     * @return 解析后的歌词文档对象
     */
    fun parse(raw: String?, duration: Long = 0): EnhanceLrcDocument {
        if (raw.isNullOrBlank()) return EnhanceLrcDocument(emptyMap(), emptyList())

        val resultLines = mutableListOf<RichLyricLine>()
        val metaData = mutableMapOf<String, String>()
        val leadVocal = mutableListOf<String>()

        raw.lineSequence().forEach { lineRaw ->
            val trimmedLine = lineRaw.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // 优先尝试匹配最常见的歌词行
            val timeMatch = TIME_TAG_REGEX.find(trimmedLine)
            if (timeMatch != null) {
                parseStandardLine(trimmedLine, leadVocal).forEach { current ->
                    val lastLine = resultLines.lastOrNull()
                    // 逻辑：合并相同开始时间的行作为副歌/背景音
                    if (lastLine != null && lastLine.begin == current.begin && lastLine.secondary == null) {
                        mergeToSecondary(lastLine, current)
                    } else {
                        resultLines.add(current)
                    }
                }
            } else {
                // 处理元数据标签 [tag: content]
                META_TAG_REGEX.matchEntire(trimmedLine)?.let { match ->
                    val tag = match.groupValues[1].lowercase()
                    val content = match.groupValues[2]

                    if (tag == "bg" && resultLines.isNotEmpty()) {
                        fillSecondaryToLine(resultLines.last(), content)
                    } else {
                        metaData[tag] = content
                    }
                }
            }
        }

        return EnhanceLrcDocument(metaData, finalizeLines(resultLines, duration))
    }

    /**
     * 解析包含时间戳的标准歌词行
     * 支持多重时间戳如 [00:10.00][00:12.00]Text
     */
    private fun parseStandardLine(
        line: String,
        leadVocal: MutableList<String>
    ): List<RichLyricLine> {
        val timeMatches = TIME_TAG_REGEX.findAll(line).toList()
        if (timeMatches.isEmpty()) return emptyList()

        // 提取文本内容：最后一个时间标签之后的部分
        val rawContent = line.substring(timeMatches.last().range.last + 1).trim()

        var person: String? = null
        var cleanContent = rawContent

        // 角色解析逻辑
        PERSON_REGEX.matchEntire(rawContent)?.let {
            val p = it.groupValues[1]
            person = p
            cleanContent = it.groupValues[2]
            if (leadVocal.isEmpty() && !p.equals("bg", true)) {
                leadVocal.add(p)
            }
        }

        val isAlignedRight = person != null && leadVocal.isNotEmpty() && person != leadVocal.first()
        val words = parseLineToWords(cleanContent)
        val textStr =
            words.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() } ?: cleanContent

        return timeMatches.map { match ->
            val ms = parseTimeToMs(match.groupValues)

            // 若有逐字时间，以第一个词的开始时间为准
            val begin = words.firstOrNull()?.begin ?: ms
            val end =
                words.lastOrNull()?.let { if (it.end > it.begin) it.end else it.begin } ?: begin

            RichLyricLine(
                begin = begin,
                end = end,
                text = textStr,
                words = words.takeIf { it.isNotEmpty() },
                isAlignedRight = isAlignedRight
            ).apply {
                // 如果有逐字稿，初步计算 duration
                if (end > begin) duration = end - begin
            }
        }
    }

    /**
     * 将背景音/翻译内容填充到主歌词行的 secondary 字段
     */
    private fun fillSecondaryToLine(line: RichLyricLine, content: String) {
        val bgWords = parseLineToWords(content)
        line.secondaryWords = bgWords.takeIf { it.isNotEmpty() }
        line.secondary = if (bgWords.isNotEmpty()) {
            bgWords.joinToString("") { it.text.orEmpty() }
        } else content

        bgWords.lastOrNull()?.end?.let { bgEnd ->
            if (bgEnd > line.end) {
                line.end = bgEnd
                line.duration = line.end - line.begin
            }
        }
    }

    /**
     * 合并两条时间戳相同的歌词行为主副关系
     */
    private fun mergeToSecondary(mainLine: RichLyricLine, bgLine: RichLyricLine) {
        mainLine.secondary = bgLine.text
        mainLine.secondaryWords = bgLine.words
        if (bgLine.end > mainLine.end) {
            mainLine.end = bgLine.end
            mainLine.duration = mainLine.end - mainLine.begin
        }
    }

    /**
     * 解析逐字时间戳文本 (例如: <00:01.10>Hello <00:01.50>World)
     *
     * @param line 不包含行时间戳的纯文本或逐字文本
     * @return 解析后的 [LyricWord] 列表
     */
    fun parseLineToWords(line: String): List<LyricWord> {
        val matches = WORD_TIME_TAG_REGEX.findAll(line).toList()
        if (matches.isEmpty()) return emptyList()

        val words = mutableListOf<LyricWord>()
        for (i in matches.indices) {
            val match = matches[i]
            val begin = parseTimeToMs(match.groupValues)

            val textStart = match.range.last + 1
            val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            val content = line.substring(textStart, textEnd)

            if (content.isNotEmpty()) {
                words.add(LyricWord(begin = begin, text = content))
            } else if (i == matches.size - 1 && words.isNotEmpty()) {
                // 处理最后一个时间锚点，作为行结束标志
                words.last().end = begin
                words.last().duration = (begin - words.last().begin).coerceAtLeast(0)
            }
        }

        // 修正逐字稿的 end 时间：当前词的 end 默认为下一个词的 begin
        words.forEachIndexed { i, word ->
            if (word.end <= word.begin) {
                words.getOrNull(i + 1)?.let { next ->
                    word.end = next.begin
                    word.duration = (next.begin - word.begin).coerceAtLeast(0)
                }
            }
        }
        return words
    }

    /**
     * 最终处理：排序、计算持续时间及兜底时间
     */
    private fun finalizeLines(
        lines: List<RichLyricLine>,
        totalDuration: Long
    ): List<RichLyricLine> {
        val sorted = lines.sortedBy { it.begin }
        sorted.forEachIndexed { index, current ->
            if (current.end <= current.begin) {
                val nextStart = sorted.getOrNull(index + 1)?.begin
                val calculatedEnd =
                    nextStart ?: if (totalDuration > 0) totalDuration else current.begin + 5000L

                // 修正当前行的 end 和 duration
                current.end = calculatedEnd
                current.duration = (calculatedEnd - current.begin).coerceAtLeast(0)
            } else {
                current.duration = (current.end - current.begin).coerceAtLeast(0)
            }
        }
        return sorted
    }

    /**
     * 将正则表达式匹配到的时间分组转换为毫秒值
     * @param groups Regex MatchResult 的 groupValues
     * index 1: hr, 2: min, 3: sec, 4: frac
     */
    private fun parseTimeToMs(groups: List<String>): Long {
        val h = groups[1].toLongOrNull() ?: 0L
        val m = groups[2].toLongOrNull() ?: 0L
        val s = groups[3].toLongOrNull() ?: 0L
        val fStr = groups.getOrNull(4).orEmpty()

        // 优化点 2: 使用字符串补位逻辑处理毫秒，比 when 分支更简洁健壮
        val ms = when {
            fStr.isEmpty() -> 0L
            fStr.length >= 3 -> fStr.substring(0, 3).toLong()
            else -> fStr.padEnd(3, '0').toLong()
        }

        return h * MS_PER_HOUR + m * MS_PER_MINUTE + s * MS_PER_SECOND + ms
    }
}