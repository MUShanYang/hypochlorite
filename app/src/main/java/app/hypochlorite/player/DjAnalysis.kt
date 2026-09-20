package app.hypochlorite.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一首曲子的 DJ 分析结果。
 *
 * 全是「尽力而为」：任何一项测不出来都不会让分析整体失败 ——
 * BPM 没有就只做能量交叉淡化，调性没有就当不合拍处理（走滤波接法）。
 * 真正 null 掉整个结果的只有「解码不出来」这一种。
 *
 * @param bpm 估计速度，0 = 没测出来（不强猜，猜错的 117.3 比没有更糟）
 * @param keyRoot 调性根音 0=C..11=B，-1 = 未知
 * @param keyMinor true = 小调
 * @param cueInMs 下一首的起混点：前奏结束、能量起来的那一小节开头
 * @param outroStartMs 当前这首的建议起切点：尾奏能量塌下来的那一拍
 */
data class TrackAnalysis(
    val bpm: Double,
    val keyRoot: Int,
    val keyMinor: Boolean,
    val cueInMs: Long,
    val outroStartMs: Long,
) {
    val hasTempo: Boolean get() = bpm >= 40.0
    val beatMs: Double get() = if (hasTempo) 60_000.0 / bpm else 500.0
    val barMs: Double get() = beatMs * 4.0

    /** Camelot 轮盘编号 1..12，0 = 未知 */
    fun camelotNumber(): Int {
        if (keyRoot !in 0..11) return 0
        // 五度圈每走一步纯五度（7 个半音）编号 +1；大调从 C=8B 起，小调从 A=8A 起
        val steps = if (keyMinor) ((keyRoot + 3) * 7) % 12 else (keyRoot * 7) % 12
        return (8 + steps - 1) % 12 + 1
    }

    fun camelotLetter(): Char = if (keyMinor) 'A' else 'B'

    /**
     * 调性合不合：同编号换大小调（关系调）、同调性相邻编号（五度圈邻居）都算合。
     * 有任意一首调性未知一律按不合处理 —— 滤波接法对任何组合都安全。
     */
    fun keyCompatible(o: TrackAnalysis): Boolean {
        val a = camelotNumber()
        val b = o.camelotNumber()
        if (a == 0 || b == 0) return false
        if (a == b) return true
        if (camelotLetter() != o.camelotLetter()) return false
        val d = abs(a - b)
        return d == 1 || d == 11
    }
}

/**
 * 曲目分析器：解码 + 节奏 + 调性 + 能量切换点。
 *
 * 整体管线：
 * 1. MediaExtractor 直接开 http(s) 流（自带 Range 请求，不用整首下载）；
 *    打不开再退回「OkHttp 整首落临时文件」。
 * 2. 只解三段：头 45s（找起混点）、中段 60s（测 BPM / 调性）、尾 100s（找切出点）。
 *    全曲解码又慢又费电，三段足够。
 * 3. 解码侧降到 ~11kHz 单声道再做 DSP —— 底鼓在 150Hz 以下，色度到 4kHz，
 *    这个采样率两头都够，FFT 和自相关的计算量降四倍。
 *
 * 全程 runCatching：网络挂了、CDN 403、codec 起不来、格式怪，一律返回 null，
 * 调用方退回普通无缝切歌，绝不让「想接得更好」变成「接不上」。
 */
object DjAnalyzer {

    /** 分析用的采样率（抽取后的目标值附近） */
    private const val ANALYSIS_SR = 11025

    /** 中段 / 头 / 尾各自的解码时长上限 */
    private const val HEAD_MS = 45_000L
    private const val MID_MS = 60_000L
    private const val TAIL_MS = 100_000L

    /** 分析入口。IO 线程上调，可能跑几秒到十几秒。失败返回 null。 */
    fun analyze(url: String, headers: Map<String, String>, durationMs: Long): TrackAnalysis? =
        runCatching { analyzeUnsafe(url, headers, durationMs) }.getOrNull()

    private fun analyzeUnsafe(url: String, headers: Map<String, String>, durationMs: Long): TrackAnalysis? {
        val decoder = SegmentDecoder()
        if (!decoder.open(url, headers)) return null
        try {
            val durMs = when {
                durationMs > 30_000L -> durationMs
                decoder.trackDurationMs > 30_000L -> decoder.trackDurationMs
                else -> return null // 太短或时长未知：没有接歌的意义
            }
            val decimate = (decoder.sampleRate / ANALYSIS_SR).coerceAtLeast(1)
            val srDec = decoder.sampleRate / decimate

            // --- 头：起混点 ---
            decoder.seekMs(0)
            val head = PcmSink(decimate)
            decoder.decodeWindow(min(HEAD_MS, durMs / 3), head)

            // --- 中段：BPM + 调性 ---
            decoder.seekMs((durMs * 0.35).toLong())
            val mid = PcmSink(decimate)
            decoder.decodeWindow(min(MID_MS, durMs / 4), mid)

            // --- 尾：切出点 ---
            val tailStart = (durMs - TAIL_MS).coerceAtLeast(0L)
            decoder.seekMs(tailStart)
            val tail = PcmSink(decimate)
            decoder.decodeWindow(TAIL_MS, tail)

            if (mid.size < srDec * 10 || head.size < srDec * 5) return null

            val bpm = estimateBpm(mid.data, mid.size, srDec)
                .takeIf { it >= 40.0 }
                ?: estimateBpm(head.data, head.size, srDec)

            val key = estimateKey(mid.data, mid.size, srDec)

            val cue = findCue(head, srDec, bpm, durMs)
            val outro = findOutro(tail, tailStart, srDec, bpm, durMs)

            return TrackAnalysis(
                bpm = bpm,
                keyRoot = key.first,
                keyMinor = key.second,
                cueInMs = cue,
                outroStartMs = outro,
            )
        } finally {
            decoder.close()
        }
    }

    // ------------------------------------------------------------------ 节奏

    /**
     * onset 包络：低通 150Hz 之后的逐 hop 对数能量差。
     * 底鼓几乎包办了流行乐的可闻节拍，全频段做会被人声和镲片带歪。
     */
    private fun onsetEnvelope(x: FloatArray, size: Int, sr: Int, hop: Int): FloatArray {
        val nHops = size / hop
        if (nHops < 16) return FloatArray(0)
        val k = (1.0 - exp(-2.0 * Math.PI * 150.0 / sr)).toFloat()
        val env = FloatArray(nHops)
        var lp = 0f
        var acc = 0f
        var prevLog = 0f
        var hopN = 0
        var hopIdx = 0
        for (i in 0 until size) {
            lp += (x[i] - lp) * k
            acc += lp * lp
            if (++hopN >= hop) {
                val logE = ln(acc / hop + 1e-9f)
                val d = logE - prevLog
                prevLog = logE
                env[hopIdx++] = if (d > 0f) d else 0f
                acc = 0f
                hopN = 0
            }
        }
        return env
    }

    /**
     * BPM 估计：onset 包络自相关 + 谐波加权 + 八度折叠。
     *
     * 只出自相关主峰容易把 2 倍 / 半速认错，所以给 lag 的倍频和半频各加 0.5 权重；
     * 最后把结果折叠进 85..170 这个「流行乐可信区间」，两头不靠就认 0（没测出来）。
     */
    private fun estimateBpm(x: FloatArray, size: Int, sr: Int): Double {
        val hop = 256
        val env = onsetEnvelope(x, size, sr, hop)
        if (env.size < 32) return 0.0
        val fps = sr.toDouble() / hop
        val n = env.size
        val lagMin = (fps * 60.0 / 200.0).toInt().coerceAtLeast(2)
        val lagMax = min((fps * 60.0 / 55.0).toInt(), n / 2)
        if (lagMax <= lagMin) return 0.0

        fun ac(lag: Int): Double {
            if (lag <= 0) return 0.0
            var a = 0.0
            var i = 0
            while (i + lag < n) {
                a += env[i] * env[i + lag]
                i++
            }
            return a
        }

        var bestLag = 0
        var bestScore = 0.0
        for (lag in lagMin..lagMax) {
            val score = ac(lag) + 0.5 * ac(lag * 2) + 0.5 * ac(lag / 2)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore <= 0.0) return 0.0
        var bpm = 60.0 * fps / bestLag
        while (bpm < 85.0) bpm *= 2.0
        while (bpm > 170.0) bpm /= 2.0
        // 峰值太弱说明包络里没有稳定周期（氛围乐 / 散板），别硬报一个数
        var total = 0.0
        for (v in env) total += v
        if (bestScore / (ac(1) + 1e-9) < 0.25 && total / n < 0.02) return 0.0
        return bpm
    }

    // ------------------------------------------------------------------ 调性

    /** Krumhansl-Schmuckler 大小调模板，相关度最高的那个就是答案 */
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /**
     * 调性估计：FFT → 折叠成 12 色度向量 → 跟 24 个模板比 Pearson 相关。
     * 色度太平（噪声 / 全打击乐）时报未知，让引擎走对任何组合都安全的滤波接法。
     */
    private fun estimateKey(x: FloatArray, size: Int, sr: Int): Pair<Int, Boolean> {
        val n = 4096
        val hop = 2048
        if (size < n * 6) return -1 to false
        val hann = FloatArray(n) { i -> (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)).toFloat() }
        val noteFreq = DoubleArray(60) { i -> 440.0 * 2.0.pow((i + 36 - 69) / 12.0) }
        val chroma = DoubleArray(12)
        val re = FloatArray(n)
        val im = FloatArray(n)
        var windows = 0
        var start = 0
        while (start + n <= size) {
            for (i in 0 until n) {
                re[i] = x[start + i] * hann[i]
                im[i] = 0f
            }
            fft(re, im)
            for (note in 0 until 60) {
                val bin = (noteFreq[note] * n / sr).roundToInt()
                var m = 0f
                for (b in bin - 1..bin + 1) {
                    if (b in 1 until n / 2) {
                        val mag = hypot(re[b], im[b])
                        if (mag > m) m = mag
                    }
                }
                chroma[(note + 36) % 12] += m
            }
            windows++
            start += hop
        }
        if (windows < 8) return -1 to false

        val mean = chroma.average()
        if (mean <= 0.0) return -1 to false
        val maxC = chroma.max()
        // 平得没有倾向的色度 = 这曲子没法谈调性
        if (maxC / mean < 1.5) return -1 to false

        var bestRoot = -1
        var bestMinor = false
        var bestCorr = -2.0
        for (root in 0..11) {
            val cMaj = pearson(chroma, MAJOR_PROFILE, root)
            if (cMaj > bestCorr) {
                bestCorr = cMaj
                bestRoot = root
                bestMinor = false
            }
            val cMin = pearson(chroma, MINOR_PROFILE, root)
            if (cMin > bestCorr) {
                bestCorr = cMin
                bestRoot = root
                bestMinor = true
            }
        }
        if (bestCorr < 0.35) return -1 to false
        return bestRoot to bestMinor
    }

    /** 色度向量与「旋转 root 格」后的模板做 Pearson 相关 */
    private fun pearson(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0..11) {
            val xv = chroma[i]
            val yv = profile[(i - root + 12) % 12]
            sx += xv
            sy += yv
            sxx += xv * xv
            syy += yv * yv
            sxy += xv * yv
        }
        val num = 12 * sxy - sx * sy
        val den = sqrt((12 * sxx - sx * sx) * (12 * syy - sy * sy))
        return if (den <= 0.0) -2.0 else num / den
    }

    /** 迭代基 2 FFT，re / im 就地变换，长度必须是 2 的幂 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var i = 0
        var j = 0
        while (i < n) {
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
            i++
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            val half = len / 2
            var s = 0
            while (s < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val a = s + k
                    val b = a + half
                    val vr = re[b] * curR - im[b] * curI
                    val vi = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                s += len
            }
            len = len shl 1
        }
    }

    // ------------------------------------------------------------------ 切换点

    /** 每 0.5s 一格的全频段 RMS 能量曲线 */
    private fun energyCurve(x: FloatArray, size: Int, sr: Int): FloatArray {
        val win = sr / 2
        val n = size / win
        val out = FloatArray(n)
        var idx = 0
        var start = 0
        while (idx < n) {
            var acc = 0f
            for (i in start until start + win) acc += x[i] * x[i]
            out[idx++] = sqrt(acc / win)
            start += win
        }
        return out
    }

    /** onset 包络的节拍相位：哪个偏移上脉冲串和包络最对齐 */
    private fun beatPhaseHop(env: FloatArray, lagHop: Int): Int {
        if (lagHop <= 0 || env.isEmpty()) return 0
        var best = 0
        var bestSum = -1f
        for (off in 0 until lagHop) {
            var s = 0f
            var i = off
            while (i < env.size) {
                s += env[i]
                i += lagHop
            }
            if (s > bestSum) {
                bestSum = s
                best = off
            }
        }
        return best
    }

    /**
     * 起混点：头 45s 里能量第一次明显起来的位置，往下对齐到小节开头。
     * 找不到（氛围 intro）就从 0 开始 —— 淡入一首没有鼓点的歌，从开头混反而自然。
     */
    private fun findCue(head: PcmSink, srDec: Int, bpm: Double, durMs: Long): Long {
        val energies = energyCurve(head.data, head.size, srDec)
        if (energies.size < 6) return 0L
        val maxE = energies.max()
        if (maxE <= 0f) return 0L
        var rise = -1
        for (i in 0 until energies.size - 1) {
            if (energies[i] > 0.30f * maxE && energies[i + 1] > 0.25f * maxE) {
                rise = i
                break
            }
        }
        if (rise < 0) return 0L
        var cueMs = head.startPtsMs + rise * 500L
        if (bpm >= 40.0) {
            // 对齐小节：包络 hop 网格上的相位换算回毫秒
            val hop = 256
            val env = onsetEnvelope(head.data, head.size, srDec, hop)
            val beatHop = (srDec.toDouble() / hop) * (60.0 / bpm)
            val barHop = beatHop * 4.0
            if (env.size >= barHop.toInt() * 2 && barHop >= 2.0) {
                val phaseHop = beatPhaseHop(env, barHop.roundToInt())
                val hopMs = hop * 1000.0 / srDec
                val phaseMs = head.startPtsMs + phaseHop * hopMs
                val bars = floor((cueMs - phaseMs) / (barHop * hopMs)).toLong()
                val snapped = (phaseMs + bars * barHop * hopMs).toLong()
                if (snapped in 0..cueMs) cueMs = snapped
            }
        }
        return cueMs.coerceIn(0L, min(30_000L, durMs / 3))
    }

    /**
     * 切出点：尾段能量跌破中位数 45% 并持续 3s 的位置，往前让 1s 再对齐到拍。
     * 没有明显收尾（直接掐断型的歌）就按「结束前 15s」给。
     */
    private fun findOutro(tail: PcmSink, tailStartMs: Long, srDec: Int, bpm: Double, durMs: Long): Long {
        val energies = energyCurve(tail.data, tail.size, srDec)
        var drop: Long = -1L
        if (energies.size >= 20) {
            val sorted = energies.sorted()
            val median = sorted[sorted.size / 2]
            if (median > 0f) {
                val begin = (energies.size * 0.3).toInt()
                for (i in begin until energies.size - 6) {
                    var low = true
                    for (j in i until i + 6) {
                        if (energies[j] >= 0.45f * median) {
                            low = false
                            break
                        }
                    }
                    if (low) {
                        drop = tailStartMs + i * 500L - 1_000L
                        break
                    }
                }
            }
        }
        var outroMs = if (drop >= 0) drop else durMs - 15_000L
        if (bpm >= 40.0) {
            val hop = 256
            val env = onsetEnvelope(tail.data, tail.size, srDec, hop)
            val beatHop = (srDec.toDouble() / hop) * (60.0 / bpm)
            if (env.size >= beatHop.toInt() * 4 && beatHop >= 1.5) {
                val phaseHop = beatPhaseHop(env, beatHop.roundToInt().coerceAtLeast(1))
                val hopMs = hop * 1000.0 / srDec
                val beatMs = 60_000.0 / bpm
                val phaseMs = tail.startPtsMs + phaseHop * hopMs
                // 往上对齐：切点落在拍上，两首歌的网格才能叠住
                val beats = kotlin.math.ceil((outroMs - phaseMs) / beatMs).toLong()
                val snapped = (phaseMs + beats * beatMs).toLong()
                if (snapped > 0) outroMs = snapped
            }
        }
        return outroMs.coerceIn((durMs * 0.5).toLong(), durMs - 5_000L)
    }

    // ------------------------------------------------------------------ 解码

    /** 简易可增长 PCM 池：装抽取后的单声道浮点，并记下首帧的绝对时间 */
    private class PcmSink(private val decimate: Int) {
        var data = FloatArray(1 shl 16)
            private set
        var size = 0
            private set
        var startPtsMs = -1L
        private var acc = 0f
        private var accN = 0

        fun offer(mono: Float, ptsMs: Long) {
            if (startPtsMs < 0) startPtsMs = ptsMs
            acc += mono
            if (++accN >= decimate) {
                if (size == data.size) data = data.copyOf(data.size * 2)
                data[size++] = acc / accN
                acc = 0f
                accN = 0
            }
        }
    }

    /**
     * 分段解码器：一个 extractor + 一个 codec，seek 后 flush 接着解。
     *
     * 直接对 http(s) URL 起 extractor —— 它自带 Range 请求，seek 到尾部
     * 不会把整首歌拉下来。CDN 要 Referer 才放行，headers 原样透传。
     */
    private class SegmentDecoder {
        private var extractor: MediaExtractor? = null
        private var codec: MediaCodec? = null
        var sampleRate = 44100
            private set
        private var channels = 2
        private var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var trackDurationMs = 0L
            private set

        fun open(url: String, headers: Map<String, String>): Boolean = runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(url, headers)
            var track = -1
            var mime = ""
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i
                    mime = m
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                    trackDurationMs = if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        f.getLong(MediaFormat.KEY_DURATION) / 1000L
                    } else {
                        0L
                    }
                    break
                }
            }
            if (track < 0) {
                ex.release()
                return@runCatching false
            }
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(ex.getTrackFormat(track), null, null, 0)
            c.start()
            runCatching {
                val of = c.outputFormat
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
            }
            extractor = ex
            codec = c
            true
        }.getOrDefault(false)

        fun seekMs(ms: Long) {
            val ex = extractor ?: return
            runCatching {
                ex.seekTo(ms * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                codec?.flush()
            }
        }

        /** 从当前位置解最多 windowMs 毫秒的音频进 sink */
        fun decodeWindow(windowMs: Long, sink: PcmSink) {
            val ex = extractor ?: return
            val c = codec ?: return
            runCatching {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var firstPts = -1L
                var idleRounds = 0
                while (!outputDone && idleRounds < 400) {
                    if (!inputDone) {
                        val idx = c.dequeueInputBuffer(10_000L)
                        if (idx >= 0) {
                            val buf = c.getInputBuffer(idx)
                            if (buf == null) {
                                inputDone = true
                            } else {
                                val n = ex.readSampleData(buf, 0)
                                if (n < 0) {
                                    c.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    c.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx >= 0 -> {
                            idleRounds = 0
                            val ptsMs = info.presentationTimeUs / 1000L
                            if (firstPts < 0) firstPts = ptsMs
                            if (ptsMs - firstPts >= windowMs) {
                                c.releaseOutputBuffer(outIdx, false)
                                outputDone = true
                            } else {
                                val buf = c.getOutputBuffer(outIdx)
                                if (buf != null && info.size > 0) {
                                    consume(buf, info.size, ptsMs, sink)
                                }
                                c.releaseOutputBuffer(outIdx, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            runCatching {
                                val of = c.outputFormat
                                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                }
                                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                            }
                        }
                        else -> idleRounds++
                    }
                }
            }
        }

        /** 一帧解码输出 → 混成单声道丢进 sink（按编码读样本） */
        private fun consume(buf: java.nio.ByteBuffer, size: Int, ptsMs: Long, sink: PcmSink) {
            buf.clear()
            buf.limit(size)
            when (pcmEncoding) {
                android.media.AudioFormat.ENCODING_PCM_16BIT -> {
                    val sb = buf.asShortBuffer()
                    val total = sb.remaining()
                    var i = 0
                    while (i + channels <= total) {
                        var acc = 0f
                        for (ch in 0 until channels) acc += sb.get(i + ch) / 32768f
                        sink.offer(acc / channels, ptsMs)
                        i += channels
                    }
                }
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = buf.asFloatBuffer()
                    val total = fb.remaining()
                    var i = 0
                    while (i + channels <= total) {
                        var acc = 0f
                        for (ch in 0 until channels) acc += fb.get(i + ch)
                        sink.offer(acc / channels, ptsMs)
                        i += channels
                    }
                }
                else -> {
                    // 24/32bit：按 4 字节整型近似读（高位有效），够用且不会崩
                    val total = size / 4
                    var i = 0
                    while (i + channels <= total) {
                        var acc = 0f
                        for (ch in 0 until channels) acc += buf.getInt((i + ch) * 4) / 2147483648f
                        sink.offer(acc / channels, ptsMs)
                        i += channels
                    }
                }
            }
        }

        fun close() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            codec = null
            extractor = null
        }
    }
}
