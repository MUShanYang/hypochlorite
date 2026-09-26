package app.hypochlorite.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 一次性端到端探针（默认跳过，只在显式要求时跑）：真歌音频 → [NcmFingerprintWasm] → 识曲接口。
 *
 * 它验的是「整条链路认得出歌」，而不是某一层的形状 —— 采样率、幅度归一、指纹编码、接口参数
 * 只要有一处不对，这里就认不出来。因为要打网络、要那份有意不进仓库的 `afp.query.wasm`，
 * 用环境变量挡住：
 * `HYPO_ONLINE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests *RealRecognitionProbeTest*`
 */
class RealRecognitionProbeTest {

    @Test
    fun `真歌的每个取样窗口都能认回自己那首`() = runBlocking {
        assumeTrue(System.getenv("HYPO_ONLINE_PROBE") == "1")
        val wasm = File("src/main/assets/netease/afp.query.wasm")
        assumeTrue("缺少 afp.query.wasm", wasm.isFile)
        assumeTrue("指纹 AOT 不可用", NcmFingerprintWasm.isAvailable())
        val generator = NcmFingerprintWasm()
        val http = OkHttpClient()

        var attempted = 0
        var hitCount = 0
        val log = StringBuilder()
        for (songId in probeSongs) {
            val audio = download("https://music.163.com/song/media/outer/url?id=$songId.mp3")
            if (audio.size < MIN_AUDIO_BYTES) {
                log.append("song $songId: 拿不到完整音频（${audio.size}B，可能要付费）\n")
                continue
            }
            val windows = takeWindows(audio, WINDOW_START_SECONDS)
            if (windows.isNullOrEmpty()) {
                log.append("song $songId: MP3 解码失败\n")
                continue
            }
            val ids = mutableListOf<String>()
            for ((startSecond, pcm) in windows) {
                attempted += 1
                val took = System.currentTimeMillis()
                val fp = generator.generate(pcm)
                val cost = System.currentTimeMillis() - took
                val hit = match(http, fp)
                val id = hit?.opt("songId")?.toString().orEmpty()
                log.append("song $songId @${startSecond}s peak=" + peakOf(pcm) + " fp=" + (fp.length * 3 / 4) + "B cost=${cost}ms -> ")
                    .append(hit?.optString("name").orEmpty()).append(" (id=").append(id).append(")\n")
                if (id.isNotEmpty() && id != "null") {
                    hitCount += 1
                    ids.add(id)
                }
            }
            // outer/url 给的那份录音在曲库里可能对应另一个条目 id，所以只能要求「各窗口一致」
            if (ids.isNotEmpty()) assertEquals("同一首歌的不同窗口该认回同一个 id：\n$log", 1, ids.toSet().size)
        }
        println(log)
        assumeTrue("一首歌的音频都没抓到，探针无法判定", attempted > 0)
        assertEquals("每个窗口都该认出一首歌：\n$log", attempted, hitCount)
    }

    /** 手动跟跳转：`outer/url` 是 https，跳转目标却是 http，JDK 默认不肯跨协议跟。 */
    private fun download(url: String): ByteArray {
        var target = url
        repeat(5) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://music.163.com/")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                target = conn.getHeaderField("Location") ?: return ByteArray(0)
                conn.disconnect()
                return@repeat
            }
            return conn.inputStream.use { it.readBytes() }
        }
        return ByteArray(0)
    }

    /**
     * 只解要取样的那几段（整首解码是几十倍白工），返回每段 8kHz 单声道 Float PCM。
     *
     * jlayer 给的是交织的 16bit 帧（`data[frame * channels + channel]`），这里顺手混成单声道
     * 并按 `1/32768` 归一 —— 和 [MediaProjectionCaptureSource] 抓出来的量纲一致。
     */
    private fun takeWindows(mp3: ByteArray, starts: List<Int>): Map<Int, FloatArray>? = runCatching {
        val bitstream = Bitstream(ByteArrayInputStream(mp3))
        val decoder = Decoder()
        val first = bitstream.readFrame() ?: return null
        val rate = first.frequency()
        val channels = if (first.mode() == Header.SINGLE_CHANNEL) 1 else 2
        val buffer = SampleBuffer(rate, channels)
        decoder.setOutputBuffer(buffer)
        val perWindow = rate * AUDIO_MATCH_SECONDS
        val buckets = starts.associateWith { FloatArray(perWindow) }
        var pos = 0L
        var header: Header? = first
        while (header != null) {
            decoder.decodeFrame(header, bitstream)
            val data = buffer.getBuffer()
            val frames = buffer.getBufferLength() / channels
            for (start in starts) {
                val bucket = buckets[start] ?: continue
                val from = start.toLong() * rate
                val to = from + perWindow
                if (pos + frames <= from || pos >= to) continue
                val localFrom = maxOf(0L, from - pos).toInt()
                val localTo = minOf(frames.toLong(), to - pos).toInt()
                val dest = (pos + localFrom - from).toInt()
                for (f in localFrom until localTo) {
                    var acc = 0
                    for (c in 0 until channels) acc += data[f * channels + c]
                    bucket[dest + f - localFrom] = acc / (channels * 32768f)
                }
            }
            pos += frames
            bitstream.closeFrame()
            buffer.clear_buffer()
            header = bitstream.readFrame()
        }
        bitstream.close()
        buckets.mapValues { (_, v) -> resampleLinear(v, rate, AUDIO_MATCH_SAMPLE_RATE) }
    }.getOrNull()

    /** 与 [app.hypochlorite.netease.NeteaseClient.audioMatch] 同样的请求形状（这里不建 client，它要 Android Context）。 */
    private fun match(http: OkHttpClient, fingerprintBase64: String): JSONObject? {
        val hex = "0123456789abcdef"
        val sessionId = (0 until 32).joinToString("") { hex[(Math.random() * 16).toInt()].toString() }
        val query = listOf(
            "sessionId" to sessionId,
            "algorithmCode" to "shazam_v2",
            "duration" to AUDIO_MATCH_SECONDS.toString(),
            "rawdata" to fingerprintBase64,
            "times" to "1",
            "decrypt" to "1",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val request = okhttp3.Request.Builder()
            .url("https://interface.music.163.com/api/music/audio/match?$query")
            .header("User-Agent", UA)
            .get()
            .build()
        val text = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val first = envelope.optJSONObject("data")?.optJSONArray("result")?.optJSONObject(0)
            ?: return JSONObject().put("name", "无结果(code=${envelope.optInt("code")})")
        return JSONObject()
            .put("songId", first.optJSONObject("song")?.opt("id"))
            .put("name", first.optJSONObject("song")?.optString("name"))
    }

    private fun peakOf(pcm: FloatArray) = abs(pcm.maxByOrNull { abs(it) } ?: 0f)

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36"
        const val MIN_AUDIO_BYTES = 200_000
        val WINDOW_START_SECONDS = listOf(20, 60, 100)

        /** 网易曲库里长期免费、`outer/url` 能直接拿到整首 MP3 的歌。 */
        val probeSongs = listOf("185801")
    }
}
