package app.hypochlorite.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 听歌识曲的输入准备 —— 识曲指纹只吃 **8kHz 单声道的归一化 Float32**，
 * 而系统音频抓取（MediaProjection 的 AudioRecord）给的是设备原生采样率的
 * 交织 PCM（绝大多数机器是 48kHz 立体声 int16），中间这两步转换就是全部工作。
 *
 * 两个函数都是纯函数，不碰 Android API，好让它们在 JVM 单元测试里跑。
 */

/** PCM 编码，取值与 [androidx.media3.common.C.ENCODING_PCM_16BIT] / `ENCODING_PCM_FLOAT` 对应。 */
enum class PcmEncoding(val bytesPerSample: Int) {
    PCM_16BIT(2),
    PCM_FLOAT(4),
}

/**
 * 交织 PCM 字节 → 归一化到 [-1, 1] 的样本，并按 `1/channelCount` 混成单声道。
 *
 * 末尾不足一个完整帧的字节直接丢掉 —— 半帧解出来是噪音，不如没有。
 */
fun downmixPcmToFloat(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    encoding: PcmEncoding,
    channelCount: Int,
): FloatArray {
    if (length <= 0 || channelCount <= 0) return FloatArray(0)
    val bytesPerFrame = encoding.bytesPerSample * channelCount
    val frames = length / bytesPerFrame
    if (frames == 0) return FloatArray(0)

    val buf = ByteBuffer.wrap(bytes, offset, frames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(frames)
    for (f in 0 until frames) {
        var acc = 0f
        for (c in 0 until channelCount) {
            acc += when (encoding) {
                PcmEncoding.PCM_16BIT -> buf.short / 32768f
                PcmEncoding.PCM_FLOAT -> buf.float.coerceIn(-1f, 1f)
            }
        }
        out[f] = acc / channelCount
    }
    return out
}

fun downmixPcmToFloat(
    bytes: ByteArray,
    encoding: PcmEncoding,
    channelCount: Int,
): FloatArray = downmixPcmToFloat(bytes, 0, bytes.size, encoding, channelCount)

/**
 * 线性插值重采样。
 *
 * 识曲指纹对相位不敏感、对**采样率**敏感（网易的提取器按 8000Hz 解读输入，
 * 送 48kHz 进去等于把音频加速 6 倍），但降采样引入的高频混叠在这条路上
 * 实测影响很小 —— 3 秒窗口、只用它做相关匹配，不是做音质还原。
 * 想更稳可以先做均值抗混叠，但那要缓存上一批样本，跨 buffer 边界更容易出错。
 */
fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return FloatArray(0)
    if (fromRate == toRate) return input.copyOf()
    val ratio = fromRate.toDouble() / toRate
    val outLength = (input.size / ratio).toInt()
    val out = FloatArray(outLength)
    for (i in 0 until outLength) {
        val src = i * ratio
        val i0 = src.toInt()
        val i1 = minOf(i0 + 1, input.size - 1)
        val frac = (src - i0).toFloat()
        out[i] = input[i0] * (1f - frac) + input[i1] * frac
    }
    return out
}

/** 抓识曲输入用的定长窗口：网易 demo 的取值，指纹长度随它固定（3 秒 → 288 字节）。 */
const val AUDIO_MATCH_SECONDS = 3

/** 识曲指纹的采样率，和网易提取器写死的值一致。 */
const val AUDIO_MATCH_SAMPLE_RATE = 8000

/**
 * 一段归一化 PCM 的峰值 [0,1]。假指纹阶段识别必无果，用它把「到底有没有抓到声音」
 * 透到界面上，作为抓取链路唯一的可验证信号。空数组给 0。
 */
fun peakOf(pcm: FloatArray): Float {
    var peak = 0f
    for (v in pcm) {
        val a = if (v < 0f) -v else v
        if (a > peak) peak = a
    }
    return peak.coerceIn(0f, 1f)
}

/**
 * 音频指纹提取器 —— [NeteaseClient.audioMatch] 的唯一上游。
 *
 * 输入是 [AUDIO_MATCH_SAMPLE_RATE] 单声道归一化 Float32（[downmixPcmToFloat] +
 * [resampleLinear] 的产物），输出是 base64 字符串；3 秒窗口定长产出 288 字节。
 * 算法在网易那份 Emscripten wasm 里，**实现只此一家**，接口留在这里是为了让
 * 抓取链路和 UI 不绑死在具体的 wasm 宿主方案上。
 */
interface AudioFingerprintGenerator {
    suspend fun generate(pcmMono8k: FloatArray): String
}
