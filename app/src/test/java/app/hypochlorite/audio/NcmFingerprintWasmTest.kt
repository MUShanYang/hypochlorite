package app.hypochlorite.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 指纹层的字节对齐测试：官方 glue（Node）跑出的 288 字节是基准，
 * Chicory 宿主必须产出**逐字节相同**的指纹。
 *
 * 需要私有的 `afp.query.wasm` 在 `app/src/main/assets/netease/` 下（见 memory：
 * 那份资源有意不进仓库），缺资源时整个测试跳过而不是失败。
 */
class NcmFingerprintWasmTest {

    private fun wasmBytes(): ByteArray? {
        val asset = File("src/main/assets/netease/afp.query.wasm")
        if (!asset.isFile) return null
        return asset.readBytes()
    }

    /** 与 golden 采集时同一份确定性音频：3 秒 @8kHz 单声道，三个音调叠加。 */
    private fun goldenPcm(): FloatArray {
        val n = AUDIO_MATCH_SECONDS * AUDIO_MATCH_SAMPLE_RATE
        return FloatArray(n) { i ->
            val t = i.toDouble() / AUDIO_MATCH_SAMPLE_RATE
            (0.25 * sin(2.0 * PI * 220.0 * t) +
                0.15 * sin(2.0 * PI * 660.0 * t) +
                0.10 * sin(2.0 * PI * 1310.0 * t)).toFloat()
        }
    }

    @Test
    fun `Chicory 宿主产出的指纹与官方 glue 逐字节相同`() = runBlocking {
        val bytes = wasmBytes()
        assumeTrue("缺少 afp.query.wasm，跳过指纹字节对齐验证", bytes != null)
        val gen = NcmFingerprintWasm(bytes!!)
        assertEquals(GOLDEN_FP_BASE64, gen.generate(goldenPcm()))
    }

    @Test
    fun `同一段音频的指纹可复现，换一段就不同`() = runBlocking {
        val bytes = wasmBytes()
        assumeTrue("缺少 afp.query.wasm，跳过指纹字节对齐验证", bytes != null)
        val gen = NcmFingerprintWasm(bytes!!)
        val pcm = goldenPcm()
        val n = pcm.size
        val other = FloatArray(n) { i ->
            val t = i.toDouble() / AUDIO_MATCH_SAMPLE_RATE
            (0.30 * sin(2.0 * PI * 415.0 * t) + 0.20 * sin(2.0 * PI * 1245.0 * t)).toFloat()
        }
        assertEquals(GOLDEN_FP_BASE64.length, gen.generate(pcm).length)
        assertEquals(false, gen.generate(other) == gen.generate(pcm))
    }

    private companion object {
        /** 3 秒 @8kHz 单声道正弦叠加上游 wasm 的输出，用官方 glue 在 Node 里取的基准。 */
        const val GOLDEN_FP_BASE64 =
            "06tAEVpcY9sP5ofacRiyGgbW77jJLWTZDFO1ikcNFyY5dOkcL+m96a7OEKzx6AiVjgT7EtuzKYB2" +
                "H30jQ8f07PcUXMNJp4nnkIbeeZgWbuMd+ScVqYj7rrai3dub6nySaYH4Avpef1wJ8sxw1WgrP71z" +
                "+tA2MBZrmwfuSt9wJ4l2OpAxcdkoXO4ADUcGUrYSRo91izS/l3Gb/ccZwelcnAsXq+fdHerUov7U" +
                "d78uHUKyHWX7wlI+WKTgQG0cBTQ2Ov1gH0YPK2WRx190nl/LqyANDNUWHWokh5mMKj819rIRCnI8" +
                "752RmgpHh7l8tdXMmsMXlIWkpfxtZjlIXYv7f1ouI63Z3/MYqdt+GTZeW5sa7uv9huTsFd57CpNc" +
                "HdCo"
    }
}
