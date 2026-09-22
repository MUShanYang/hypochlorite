package app.hypochlorite.player

import app.hypochlorite.netease.LyricLine

object Lyrics {
    /**
     * 网易给纯音乐塞的占位句。整句就是「纯音乐，请欣赏」这类，不是真歌词。
     */
    fun isInstrumentalPlaceholder(text: String): Boolean {
        val compact = buildString(text.length) {
            for (c in text) {
                if (!c.isWhitespace() && c !in "，,。、.！!？?~…·") append(c)
            }
        }
        if (compact.isEmpty()) return false
        return compact == "纯音乐请欣赏" ||
            compact == "纯音乐请您欣赏" ||
            compact == "纯音乐请你欣赏" ||
            compact == "此歌曲为没有填词的纯音乐请欣赏" ||
            compact == "此歌曲为没有填词的纯音乐请您欣赏"
    }

    fun withoutPlaceholders(lines: List<LyricLine>): List<LyricLine> =
        lines.filterNot { isInstrumentalPlaceholder(it.text) }

    /**
     * Whether the now-playing [查看歌词] control / lyrics page should be available.
     *
     * While the current song's lyrics are still loading, keep the last resolved
     * answer so the control does not flash hide→show (or show→hide) on every cut.
     */
    fun lyricsAvailable(resolved: Boolean, hasLines: Boolean, lastKnown: Boolean): Boolean =
        if (resolved) hasLines else lastKnown

    /**
     * 扫光前沿，0 = 还没进来，1 = 整行扫完。
     * 扫光底和字色必须用同一个值、同一段宽度，否则底先扫完字还没变完。
     */
    fun sweepReveal(wipe: Float): Float = (1f + wipe).coerceIn(0f, 1f)

    /**
     * Mask along a lyric-viewport edge. [t] is 0 at the lip (wave / title) and 1
     * toward the reading area. Ease-out so the dissolve sits on the chrome
     * instead of a linear wipe that leaves a dead band.
     */
    fun edgeReveal(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    /**
     * How long [index] stays current: until the next line, or 8s on the last line
     * (same window as [currentIndex]).
     */
    fun lineHoldMs(lines: List<LyricLine>, index: Int): Long {
        if (index !in lines.indices) return 0L
        val start = lines[index].timeMs
        val end = if (index + 1 < lines.size) lines[index + 1].timeMs else start + 8_000L
        return (end - start).coerceAtLeast(0L)
    }

    /**
     * Stretch or compress a designed motion to a line's hold.
     *
     * [baseMs] is the authored duration (620 / 550 / 400 / 580) and the
     * ceiling: a typical 2s line maps back to that base, shorter holds speed up,
     * longer holds stay at [baseMs] so an 8s tail does not crawl.
     * Pair with [switchEaseMix] so a compressed motion does not keep the whippy curve.
     */
    fun switchDurationMs(
        holdMs: Long,
        baseMs: Int,
        minMs: Int = (baseMs * 2 / 5).coerceAtLeast(1),
        maxMs: Int = baseMs.coerceAtLeast(minMs),
        holdRefMs: Long = 2_000L,
    ): Int {
        if (baseMs <= 0) return 0
        if (holdMs <= 0L || holdRefMs <= 0L) return baseMs.coerceAtMost(maxMs)
        val scaled = (baseMs.toLong() * holdMs / holdRefMs).toInt()
        return scaled.coerceIn(minMs, maxMs)
    }

    /**
     * How much of the authored ease to keep. 1 = full whip (2s line),
     * 0 = the soft short-hold curve. Compressed durations would otherwise sit
     * still then slam.
     */
    fun switchEaseMix(durationMs: Int, baseMs: Int): Float {
        if (baseMs <= 0) return 1f
        val minMs = (baseMs * 2 / 5).coerceAtLeast(1)
        if (durationMs >= baseMs) return 1f
        if (durationMs <= minMs) return 0f
        return (durationMs - minMs).toFloat() / (baseMs - minMs).toFloat()
    }

    /**
     * Which line the full-lyrics list should chase.
     *
     * [currentIndex] is −1 when nothing is active (intro, or last line already expired).
     * After the list has been placed, return null so the viewport does not jump home to line 0.
     * First paint may park on 0 so LazyColumn has geometry.
     */
    fun scrollTarget(currentIndex: Int, lineCount: Int, alreadyFollowing: Boolean): Int? {
        if (lineCount <= 0) return null
        if (currentIndex in 0 until lineCount) return currentIndex
        if (!alreadyFollowing && currentIndex < 0) return 0
        return null
    }

    /**
     * Visible window: current line until the next starts; last line expires after 8s.
     * Lines are time-sorted. Binary search keeps the 200ms clock off a linear scan.
     */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                i = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (i < 0) return -1
        val end = if (i + 1 < lines.size) lines[i + 1].timeMs else lines[i].timeMs + 8_000L
        if (positionMs >= end) return -1
        return i
    }

    private fun isPunctuation(c: Char): Boolean {
        if (c.isWhitespace()) return true
        val type = Character.getType(c).toByte()
        return when (type) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.MODIFIER_SYMBOL,
            Character.OTHER_SYMBOL -> true
            else -> c in "，。！？、：；“”‘’（）()[]{}《》〈〉【】—…~-–.,!?:;'\"/\\`#$^&*+=|"
        }
    }

    /**
     * Split a lyric into vertical columns (top-to-bottom) with balanced and
     * clause-aware typography.
     * Punctuation marks are utilized as natural clause boundaries to avoid
     * breaking words, and lengths are symmetrically balanced to eliminate
     * orphan characters (e.g. 7+7 instead of 11+1).
     */
    fun columns(text: String, maxPerCol: Int = 8, maxCols: Int = 3): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()

        // 1. 尝试按标点符号与空格拆分为自然分句
        val clauses = mutableListOf<String>()
        val currentClause = StringBuilder()
        for (c in raw) {
            if (isPunctuation(c)) {
                if (currentClause.isNotEmpty()) {
                    clauses.add(currentClause.toString())
                    currentClause.clear()
                }
            } else {
                currentClause.append(c)
            }
        }
        if (currentClause.isNotEmpty()) {
            clauses.add(currentClause.toString())
        }

        if (clauses.isEmpty()) return emptyList()

        val cleanText = clauses.joinToString("")
        val totalLen = cleanText.length
        if (totalLen <= maxPerCol) {
            return listOf(cleanText)
        }

        // 2. 如果存在天然标点停顿，且两句均在限长内，按诗歌停顿分栏
        if (clauses.size == 2 && clauses[0].length <= maxPerCol && clauses[1].length <= maxPerCol) {
            return listOf(clauses[0], clauses[1])
        }

        if (clauses.size in 2..maxCols) {
            // 多个分句：寻找在分句之间最接近居中对齐的分割点
            var bestSplit = -1
            var bestDiff = Int.MAX_VALUE
            var leftLen = 0
            for (i in 0 until clauses.size - 1) {
                leftLen += clauses[i].length
                val rightLen = totalLen - leftLen
                if (leftLen <= maxPerCol && rightLen <= maxPerCol) {
                    val diff = kotlin.math.abs(leftLen - rightLen)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestSplit = i
                    }
                }
            }
            if (bestSplit >= 0) {
                val left = clauses.subList(0, bestSplit + 1).joinToString("")
                val right = clauses.subList(bestSplit + 1, clauses.size).joinToString("")
                return listOf(left, right)
            }
        }

        // 3. 超过两栏限长时支持三栏平衡排版（如 21 字分为 7+7+7，24 字分为 8+8+8）
        if (maxCols >= 3 && totalLen > maxPerCol * 2) {
            val countPerCol = (totalLen + 2) / 3
            val split1 = countPerCol.coerceIn(1, maxPerCol)
            val c1 = cleanText.substring(0, split1)
            val rem = cleanText.substring(split1)
            val halfRem = (rem.length + 1) / 2
            val split2 = halfRem.coerceIn(1, maxPerCol)
            val c2 = rem.substring(0, split2)
            val c3 = rem.substring(split2).take(maxPerCol)
            return listOfNotNull(c1, c2, c3.ifEmpty { null })
        }

        // 4. 无标点或长句：对称平衡折半排版（如 14 字分为 7+7，15 字分为 8+7，消除孤字怪状）
        val half = (totalLen + 1) / 2
        val splitIndex = half.coerceIn(1, maxPerCol)
        val col1 = cleanText.substring(0, splitIndex)
        val remaining = cleanText.substring(splitIndex)

        return if (remaining.length <= maxPerCol) {
            listOf(col1, remaining)
        } else {
            val col2 = remaining.take(maxPerCol)
            listOf(col1, col2)
        }
    }

    /**
     * Check if a lyric line is English/Latin. English lines contain Latin characters
     * and no CJK characters.
     */
    fun isEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        val hasLatin = text.any { (it in 'A'..'Z') || (it in 'a'..'z') || (it.code in 0x00C0..0x024F) }
        val hasCjk = text.any { c ->
            val code = c.code
            (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x3040..0x30FF) ||
            (code in 0xAC00..0xD7AF)
        }
        return hasLatin && !hasCjk
    }

    /**
     * Automatically wrap parenthetical content to a new line if preceded by other text.
     */
    fun wrapParentheses(text: String): String {
        if (text.isBlank()) return text
        return text.replace(Regex("""(?<!^)(?<!\n)\s*([（(\[【])""")) { "\n" + it.groupValues[1] }
    }

    data class LyricSplit(
        val vertical: String?,
        val horizontal: String?,
    )

    /**
     * Splits lyric into vertical (CJK/Chinese) and horizontal (English/Latin) components.
     * If a line has both English and CJK, the English is separated out to be placed at the bottom,
     * leaving only CJK characters to be rendered vertically.
     */
    fun splitLyric(rawText: String): LyricSplit {
        val text = rawText.trim()
        if (text.isEmpty()) return LyricSplit(null, null)

        val hasLatin = text.any { (it in 'A'..'Z') || (it in 'a'..'z') }
        val hasCjk = text.any { c ->
            val code = c.code
            (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x3040..0x30FF) ||
            (code in 0xAC00..0xD7AF)
        }

        // Case 1: Purely CJK (no English words)
        if (hasCjk && !hasLatin) {
            return LyricSplit(vertical = wrapParentheses(text), horizontal = null)
        }

        // Case 2: Purely English (no CJK)
        if (hasLatin && !hasCjk) {
            return LyricSplit(vertical = null, horizontal = wrapParentheses(text))
        }

        // Case 3: Mixed CJK and English - split them out
        val latinPattern = Regex("""[A-Za-z0-9'’\-][A-Za-z0-9'’\-\s.,!?]*[A-Za-z0-9'’]|[A-Za-z]""")
        val latinMatches = latinPattern.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

        var cjkPart = text
        for (m in latinMatches) {
            cjkPart = cjkPart.replace(m, "")
        }
        cjkPart = cjkPart
            .replace(Regex("""[（(\[【]\s*[）)\]】]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '，', '-', '—', '、', ';', '；')

        val enPart = latinMatches.joinToString(" ")
        val finalVertical = if (cjkPart.any { c ->
            val code = c.code
            (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) || (code in 0x3040..0x30FF) || (code in 0xAC00..0xD7AF)
        }) wrapParentheses(cjkPart) else null

        val finalHorizontal = if (enPart.isNotBlank()) wrapParentheses(enPart) else null

        return LyricSplit(vertical = finalVertical, horizontal = finalHorizontal)
    }
}
