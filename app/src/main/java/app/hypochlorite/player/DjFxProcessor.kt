package app.hypochlorite.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * DJ 音效处理器：增益 / 高低通扫频 / 节拍回响 / 混响，四合一挂在音频管道里。
 *
 * 为什么是一个处理器而不是四个：交叉淡化时这几样是**同时**动的（淡出那首
 * 一边压音量一边切低音一边撒回响），分开挂四个处理器没法共享一份参数快照，
 * 主线程 200ms 一跳地各改各的，参数之间会错开一拍，听感发脏。
 *
 * 用法：主线程（或任何线程）调 [setParams] 换一份新参数，音频线程按 ~10ms
 * 时间常数逐样本平滑过去 —— 参数跳变不会产生拉链噪声。所以控制端可以
 * 放心按 tick（200ms）节奏推参数，平滑在这里完成。
 *
 * 格式宽容：16/24/32bit 整型与 float 都处理；遇到不认识的编码**直通**而不是
 * 抛异常 —— 抛 UnhandledAudioFormatException 会让整条音频轨建不起来，
 * 高码率 FLAC 会莫名其妙没声，这个坑比「音效没生效」严重得多。
 *
 * 链路位置：media3 自定义 AudioProcessor 在变速（Sonic）**之前**。
 * 对我们的用途没有影响：滤波/回响对原速信号做，变速在下游完成。
 */
class DjFxProcessor : BaseAudioProcessor() {

    @Volatile private var visualLevel = 0f
    @Volatile private var visualSampleNanos = 0L

    fun audioLevel(): Float =
        if (System.nanoTime() - visualSampleNanos < 250_000_000L) visualLevel else 0f

    // Sample the processed PCM without changing its position or the audible output.
    private fun measureOutput(buffer: ByteBuffer) {
        val samples = buffer.remaining() / bytesPerSample
        val stride = (samples / 256).coerceAtLeast(1)
        var energy = 0.0
        var count = 0
        var i = 0
        while (i < samples) {
            val value = readSample(buffer, buffer.position() + i * bytesPerSample)
            if (value.isFinite()) {
                energy += value * value
                count++
            }
            i += stride
        }
        visualLevel = if (count > 0) kotlin.math.sqrt(energy / count).toFloat() else 0f
        visualSampleNanos = System.nanoTime()
    }

    /**
     * 一份完整的音效参数。不可变，替换即生效。
     *
     * @param gain 线性增益，交叉淡化时 0..1 扫动
     * @param lpCutoffHz 低通截止频率，0 = 关闭（全通）
     * @param hpCutoffHz 高通截止频率，0 = 关闭
     * @param echoMix 回响（delay）湿声比例 0..1
     * @param echoDelaySec 回响延迟秒数（引擎按节拍算好传进来）
     * @param echoFeedback 回响反馈量 0..0.9
     * @param reverbMix 混响湿声比例 0..1
     */
    data class FxParams(
        val gain: Float = 1f,
        val lpCutoffHz: Float = 0f,
        val hpCutoffHz: Float = 0f,
        val echoMix: Float = 0f,
        val echoDelaySec: Float = 0.375f,
        val echoFeedback: Float = 0.42f,
        val reverbMix: Float = 0f,
    ) {
        /** 全中性时走快速拷贝路径，一点 CPU 都不多花 */
        fun isNeutral(): Boolean =
            gain > 0.999f && lpCutoffHz <= 0f && hpCutoffHz <= 0f &&
                echoMix <= 0.0001f && reverbMix <= 0.0001f
    }

    @Volatile
    private var target: FxParams = FxParams()

    /** 换一份目标参数。线程安全，音频线程下一帧就开始朝它平滑。 */
    fun setParams(p: FxParams) {
        target = p
    }

    /** 立刻回到全中性（取消过渡 / 换歌时调用） */
    fun resetFx() {
        target = FxParams()
    }

    // ------------------------------------------------------------------ 音频线程状态

    /** 平滑后的当前值（仅音频线程读写） */
    private var curGain = 1f
    private var curLp = 0f
    private var curHp = 0f
    private var curEchoMix = 0f
    private var curReverbMix = 0f
    private var smoothK = 0.2f

    private var bypass = false
    private var channels = 2
    private var sampleRate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var bytesPerSample = 2

    private var lpFilters = emptyArray<Biquad>()
    private var hpFilters = emptyArray<Biquad>()
    private var lpAppliedHz = -1f
    private var hpAppliedHz = -1f

    private var echoLines = emptyArray<DelayLine>()
    private var echoAppliedSec = -1f

    private var reverb = emptyArray<ReverbChannel>()

    // ------------------------------------------------------------------ AudioProcessor

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        encoding = inputAudioFormat.encoding
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> {
                // 不认识的编码：直通，绝不抛异常（见类头注释）
                bypass = true
                bytesPerSample = 2
                return inputAudioFormat
            }
        }
        bypass = false
        // 平滑时间常数 ~10ms：参数从旧值滑到新值，耳朵听不到台阶
        smoothK = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()
        lpFilters = Array(channels) { Biquad() }
        hpFilters = Array(channels) { Biquad() }
        lpAppliedHz = -1f
        hpAppliedHz = -1f
        val t = target
        echoLines = makeEchoLines(t.echoDelaySec)
        echoAppliedSec = t.echoDelaySec
        reverb = Array(channels) { ch -> ReverbChannel(sampleRate, ch) }
        // 换轨时状态清零，直接吸附到目标值，避免旧尾巴带进新轨
        curGain = t.gain
        curLp = t.lpCutoffHz
        curHp = t.hpCutoffHz
        curEchoMix = t.echoMix
        curReverbMix = t.reverbMix
        return inputAudioFormat
    }

    override fun onFlush() {
        visualLevel = 0f
        visualSampleNanos = 0L
        lpFilters.forEach { it.reset() }
        hpFilters.forEach { it.reset() }
        echoLines.forEach { it.clear() }
        reverb.forEach { it.reset() }
        lpAppliedHz = -1f
        hpAppliedHz = -1f
        val t = target
        curGain = t.gain
        curLp = t.lpCutoffHz
        curHp = t.hpCutoffHz
        curEchoMix = t.echoMix
        curReverbMix = t.reverbMix
    }

    override fun onReset() {
        visualLevel = 0f
        visualSampleNanos = 0L
        target = FxParams()
        curGain = 1f
        curLp = 0f
        curHp = 0f
        curEchoMix = 0f
        curReverbMix = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (bypass) {
            // 直通：原样搬走
            out.put(inputBuffer)
            out.flip()
            return
        }
        val frameSize = bytesPerSample * channels
        val frames = remaining / frameSize
        val start = inputBuffer.position()
        val t = target

        // 延迟秒数变了（换了一首 BPM 不同的歌）→ 重建延迟线
        if (t.echoDelaySec != echoAppliedSec) {
            echoLines = makeEchoLines(t.echoDelaySec)
            echoAppliedSec = t.echoDelaySec
        }

        // 全中性快速路径：淡完了 / 没开 DJ 时一帧一帧算纯属浪费。
        // 必须整块搬（put(ByteBuffer) 是本地批量拷贝），逐字节搬在音频线程上
        // 是每秒十几万次带边界检查的虚调用 —— 换歌那一瞬 CPU 最紧，会听出来。
        val neutral = t.isNeutral() &&
            curGain > 0.999f && curLp <= 0.01f && curHp <= 0.01f &&
            curEchoMix <= 0.0001f && curReverbMix <= 0.0001f &&
            echoSilent() && reverbSilent()
        if (neutral) {
            out.put(inputBuffer)
            out.flip()
            measureOutput(out)
            return
        }

        var pos = start
        for (f in 0 until frames) {
            // 每帧平滑一次参数（帧 ≈ 23us，等价于逐样本平滑，省一半运算）
            curGain += (t.gain - curGain) * smoothK
            curLp += (t.lpCutoffHz - curLp) * smoothK
            curHp += (t.hpCutoffHz - curHp) * smoothK
            curEchoMix += (t.echoMix - curEchoMix) * smoothK
            curReverbMix += (t.reverbMix - curReverbMix) * smoothK

            // 截止频率平滑到位后才重算一次系数；变化 <0.5% 不动，省三角函数
            if (curLp > 0.5f && differs(lpAppliedHz, curLp)) {
                val hz = curLp.coerceIn(60f, sampleRate * 0.45f)
                lpFilters.forEach { it.setLowpass(sampleRate, hz) }
                lpAppliedHz = curLp
            }
            if (curHp > 0.5f && differs(hpAppliedHz, curHp)) {
                val hz = curHp.coerceIn(30f, sampleRate * 0.45f)
                hpFilters.forEach { it.setHighpass(sampleRate, hz) }
                hpAppliedHz = curHp
            }

            for (ch in 0 until channels) {
                var x = readSample(inputBuffer, pos)
                pos += bytesPerSample

                x *= curGain
                if (curHp > 0.5f) x = hpFilters[ch].process(x)
                if (curLp > 0.5f) x = lpFilters[ch].process(x)

                // 回响：延迟线里永远在读（尾巴要放完），写入含反馈；
                // 湿声按平滑后的比例混入。mix 归 0 后尾巴自然衰减干净。
                val line = echoLines[ch]
                val wet = line.read()
                line.write(x + wet * t.echoFeedback)
                if (curEchoMix > 0.0001f || line.alive()) {
                    x = x * (1f - curEchoMix) + wet * curEchoMix
                }

                if (curReverbMix > 0.0001f || reverb[ch].alive()) {
                    val rw = reverb[ch].process(x)
                    x = x * (1f - curReverbMix) + rw * curReverbMix
                }

                writeSample(out, x)
            }
        }
        inputBuffer.position(start + remaining)
        out.flip()
        measureOutput(out)
    }

    // ------------------------------------------------------------------ 样本读写（各编码）

    private fun readSample(buf: ByteBuffer, bytePos: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buf.getShort(bytePos) / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get(bytePos).toInt() and 0xFF
            val b1 = buf.get(bytePos + 1).toInt() and 0xFF
            val b2 = buf.get(bytePos + 2).toInt()
            ((b2 shl 24) or (b1 shl 16) or (b0 shl 8)) / 2147483648f
        }
        C.ENCODING_PCM_32BIT -> buf.getInt(bytePos) / 2147483648f
        C.ENCODING_PCM_FLOAT -> buf.getFloat(bytePos).coerceIn(-1.5f, 1.5f)
        else -> 0f
    }

    private fun writeSample(out: ByteBuffer, v: Float) {
        val x = v.coerceIn(-1f, 1f)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> out.putShort((x * 32767f).toInt().toShort())
            C.ENCODING_PCM_24BIT -> {
                val iv = (x * 2147483647f).toInt()
                out.put((iv shr 8).toByte())
                out.put((iv shr 16).toByte())
                out.put((iv shr 24).toByte())
            }
            C.ENCODING_PCM_32BIT -> out.putInt((x * 2147483647f).toInt())
            C.ENCODING_PCM_FLOAT -> out.putFloat(v.coerceIn(-1.5f, 1.5f))
        }
    }

    // ------------------------------------------------------------------ 内部 DSP 件

    private fun differs(applied: Float, cur: Float): Boolean =
        applied <= 0f || kotlin.math.abs(cur - applied) / applied > 0.005f

    private fun makeEchoLines(delaySec: Float): Array<DelayLine> {
        val base = (delaySec.coerceIn(0.08f, 0.9f) * sampleRate).toInt().coerceAtLeast(64)
        return Array(channels) { ch ->
            // 左右错开 23 个样本，湿声在声场里铺开一点，不像干打拍
            DelayLine(base + if (ch % 2 == 1) 23 else 0)
        }
    }

    private fun echoSilent(): Boolean = echoLines.none { it.alive() }

    private fun reverbSilent(): Boolean = reverb.none { it.alive() }

    /** RBJ 双二阶（Butterworth，Q=0.7071）。LP 扫闷、HP 切低音都靠它。 */
    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun setLowpass(sr: Int, hz: Float) {
            val w0 = 2.0 * PI * hz / sr
            val alpha = sin(w0) / (2.0 * 0.7071)
            val cosw = cos(w0)
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosw) / 2.0 / a0).toFloat()
            b1 = ((1.0 - cosw) / a0).toFloat()
            b2 = b0
            a1 = (-2.0 * cosw / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun setHighpass(sr: Int, hz: Float) {
            val w0 = 2.0 * PI * hz / sr
            val alpha = sin(w0) / (2.0 * 0.7071)
            val cosw = cos(w0)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw) / 2.0 / a0).toFloat()
            b1 = (-(1.0 + cosw) / a0).toFloat()
            b2 = b0
            a1 = (-2.0 * cosw / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }

    /** 简单反馈延迟线。read 必须先于 write 调（读出旧值再写入新混合）。 */
    private class DelayLine(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(16))
        private var idx = 0
        private var energy = 0f

        fun read(): Float = buf[idx]

        fun write(v: Float) {
            buf[idx] = v
            idx = (idx + 1) % buf.size
            // 粗略活跃度：用于「尾巴放完了没」的快速判断，不追求精确
            energy = energy * 0.999f + kotlin.math.abs(v) * 0.001f
        }

        fun alive(): Boolean = energy > 0.0002f

        fun clear() {
            buf.fill(0f)
            idx = 0
            energy = 0f
        }
    }

    /**
     * 一个声道的 Schroeder 混响：4 个并联反馈梳状 + 2 个串联全通。
     * 延迟按 44.1k 的经典值缩放，声道间错开 23 样本造立体声宽度。
     * 这不是厅堂级混响 —— 它的任务只是给淡出那首铺一层「尾巴雾」，
     * 把切走的边界藏起来，小房间感刚好。
     */
    private class ReverbChannel(sampleRate: Int, channel: Int) {
        private val scale = sampleRate / 44100f
        private val spread = if (channel % 2 == 1) 23 else 0
        private val combs = intArrayOf(1116, 1188, 1277, 1356).map {
            Comb((it * scale).toInt() + spread, 0.78f)
        }
        private val allpasses = intArrayOf(556, 441).map {
            Allpass((it * scale).toInt() + spread, 0.5f)
        }
        private var energy = 0f

        fun process(x: Float): Float {
            var acc = 0f
            for (c in combs) acc += c.process(x)
            var y = acc * 0.25f
            for (a in allpasses) y = a.process(y)
            energy = energy * 0.9995f + kotlin.math.abs(y) * 0.0005f
            return y
        }

        fun alive(): Boolean = energy > 0.0002f

        fun reset() {
            combs.forEach { it.clear() }
            allpasses.forEach { it.clear() }
            energy = 0f
        }
    }

    private class Comb(size: Int, private val feedback: Float) {
        private val buf = FloatArray(size.coerceAtLeast(8))
        private var idx = 0

        fun process(x: Float): Float {
            val out = buf[idx]
            buf[idx] = x + out * feedback
            idx = (idx + 1) % buf.size
            return out
        }

        fun clear() {
            buf.fill(0f)
            idx = 0
        }
    }

    private class Allpass(size: Int, private val g: Float) {
        private val buf = FloatArray(size.coerceAtLeast(8))
        private var idx = 0

        fun process(x: Float): Float {
            val b = buf[idx]
            val out = b - g * x
            buf[idx] = x + b * g
            idx = (idx + 1) % buf.size
            return out
        }

        fun clear() {
            buf.fill(0f)
            idx = 0
        }
    }
}
