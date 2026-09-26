package app.hypochlorite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真·系统音频抓取：从 [SystemAudioController] 拿当前 MediaProjection，用 AudioPlaybackCapture
 * 录 [durationSeconds] 秒，转成 [AUDIO_MATCH_SAMPLE_RATE] 单声道 Float32（识曲指纹的唯一输入）。
 *
 * 只在 Android 10(API 29)+ 有意义（AudioPlaybackCapture 是那时加的），由控制器 [SystemAudioController.supported]
 * 把关；低版本根本不会构造到这里。
 *
 * **一个 MediaProjection 只能开一次采集会话**（14+ 复用直接 SecurityException，官方文档原话
 * 「单次使用」），所以 [stream] 全程只建一个 AudioRecord，一次点按的所有分段都从它读；整条链
 * 跑完（或用户中止）才在 `finally` 里结束会话 —— 逐段重开 AudioRecord 会当场炸。
 */
class MediaProjectionCaptureSource(
    private val controller: SystemAudioController,
) : AudioCaptureSource {

    override suspend fun capture(durationSeconds: Int): FloatArray {
        var out = FloatArray(0)
        stream(durationSeconds, maxChunks = 1) {
            out = it
            false
        }
        return out
    }

    override suspend fun stream(
        chunkSeconds: Int,
        maxChunks: Int,
        onChunk: suspend (FloatArray) -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val projection = controller.projection
            ?: throw IllegalStateException("系统音频未授权，点中间那个方块授权录屏")

        // 只按 usage 匹配、**从不调用 removeMatchingUids** —— 也就是说我们自己的 ExoPlayer
        // 同样在采集集合里。所以识别前必须先把自家播放暂停掉（见
        // HypochloriteViewModel.pauseOwnForCapture），否则用户点一下识曲，指纹录的是自己。
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(InputRateHz)
            // AudioPlaybackCapture 会把多声道重混成我们请求的单声道，无需手动取左声道
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(InputRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("设备不支持 ${InputRateHz}Hz 单声道抓取")
        // 缓冲区要能压住「算上一段指纹时新进来的声音」：算一段要秒级，所以给到 chunk+1 秒。
        // 真满了也不会错，只是丢样本、分段边界往后漂。
        val bufferBytes = maxOf(minBuf * 2, InputRateHz * 2 * (chunkSeconds + 1))
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("录音初始化失败，可能被别的占用或系统拒绝")
            }
            record.startRecording()
            try {
                for (i in 0 until maxChunks) {
                    val pcm = resampleLinear(readChunk(record, chunkSeconds), InputRateHz, AUDIO_MATCH_SAMPLE_RATE)
                    if (!onChunk(pcm)) break
                }
            } finally {
                runCatching { record.stop() }
            }
        } finally {
            runCatching { record.release() }
            // 这一轮识别到此为止，会话作废：ready 翻回 false，下次点按会重新弹授权框
            // （想省掉那次弹框就会换来「点一下就秒失败」）。
            controller.endCapture()
        }
    }

    /** 读满 [seconds] 秒并归一化成 [-1,1]；读不满（超时）就返回手上这些，尾部不留静音。 */
    private fun readChunk(record: AudioRecord, seconds: Int): FloatArray {
        val need = seconds * InputRateHz
        val pcm = ShortArray(need)
        var filled = 0
        val buffer = ShortArray(ReadStep)
        val deadline = System.currentTimeMillis() + seconds * 1000L + CaptureGraceMs
        while (filled < need) {
            val read = record.read(buffer, 0, minOf(buffer.size, need - filled))
            if (read <= 0) {
                if (System.currentTimeMillis() > deadline) break
                else continue
            }
            System.arraycopy(buffer, 0, pcm, filled, read)
            filled += read
        }
        return FloatArray(filled) { i -> pcm[i] / 32768f }
    }

    private companion object {
        const val InputRateHz = 16_000
        const val CaptureGraceMs = 1_500L
        const val ReadStep = 4096
    }
}
