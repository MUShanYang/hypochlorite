package app.hypochlorite.audio

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * 识曲的音频来源。返回 [AUDIO_MATCH_SAMPLE_RATE] 单声道、归一化到 [-1, 1] 的
 * Float32 PCM，长度 [AUDIO_MATCH_SECONDS] * 采样率。
 *
 * 真实现（MediaProjection 抓系统音频 + [downmixPcmToFloat]/[resampleLinear]）
 * 是第 6 步的事；本阶段用 [SineAudioCaptureSource] 把上层链路跑通。
 */
fun interface AudioCaptureSource {
    /**
     * 采集一段定长音频。实现应当真的占用约 [durationSeconds] 的时间窗口
     * （录音本就要这么久），好让 Capturing 态的动效有对应时长可看。
     */
    suspend fun capture(durationSeconds: Int): FloatArray
}

/**
 * 正弦假源：产出形状正确、可复现的 3 秒音频，只用于打通 UI。
 *
 * 它按真实时长 delay，所以识别中的环能转满 3 秒，而不是瞬间跳过。
 */
class SineAudioCaptureSource : AudioCaptureSource {
    override suspend fun capture(durationSeconds: Int): FloatArray {
        val n = durationSeconds * AUDIO_MATCH_SAMPLE_RATE
        delay(durationSeconds * 1000L)
        return FloatArray(n) { i ->
            val t = i / AUDIO_MATCH_SAMPLE_RATE.toFloat()
            // 三个音调叠加，避免纯单音在某处过零变平；幅度留足头部空间
            0.25f * sin(2f * PI.toFloat() * 220f * t) +
                0.15f * sin(2f * PI.toFloat() * 660f * t) +
                0.10f * sin(2f * PI.toFloat() * 1310f * t)
        }
    }
}

/**
 * 假指纹生成器：返回一段 288 字节的 base64，长度落在真指纹的量级上、内容无意义。
 *
 * 真指纹层（[NcmFingerprintWasm]）已经接管正常路径，这里只剩一个兜底：装机的 APK 里
 * 没有私有的 `afp.query.wasm` 资源时用它，链路仍能跑完（拿它打真接口必然
 * `code 200 + result=null`，见 memory，所以只会「没听出来」而不是崩）。
 * 和 [SineAudioCaptureSource] 一样，它 delay 一小段模拟真实提取的耗时，
 * 让 Fingerprinting 态在动效里可见。
 */
class FakeAudioFingerprintGenerator : AudioFingerprintGenerator {
    override suspend fun generate(pcmMono8k: FloatArray): String {
        delay(FingerprintFakeMillis)
        return FixedFingerprintBase64
    }

    private companion object {
        /** 真提取器在 Chicory 上单次 2.3~3.4s（JVM 实测，Node 原生 wasm 是 30~75ms），兜底时不必真等那么久。 */
        const val FingerprintFakeMillis = 300L

        /** 288 字节 → 384 base64 字符。真指纹长度随内容变（实测 3 秒 738~786 字节），这定值只是同量级的占位。 */
        val FixedFingerprintBase64: String =
            ByteArray(288) { ((it * 37 + 11) % 251).toByte() }.let { bytes ->
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
    }
}
