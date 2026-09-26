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
 */
class MediaProjectionCaptureSource(
    private val controller: SystemAudioController,
) : AudioCaptureSource {

    override suspend fun capture(durationSeconds: Int): FloatArray = withContext(Dispatchers.IO) {
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
        // 双份 minBuf 抗抖动；capture config 决定了实际来源，无需再 setAudioSource
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("录音初始化失败，可能被别的占用或系统拒绝")
            }
            val need = durationSeconds * InputRateHz
            val pcm = ShortArray(need)
            var filled = 0
            val chunk = ShortArray(minBuf.coerceAtLeast(1024))
            record.startRecording()
            try {
                val deadline = System.currentTimeMillis() + durationSeconds * 1000L + CaptureGraceMs
                while (filled < need) {
                    val read = record.read(chunk, 0, minOf(chunk.size, need - filled))
                    if (read <= 0) {
                        if (System.currentTimeMillis() > deadline) break
                        else continue
                    }
                    System.arraycopy(chunk, 0, pcm, filled, read)
                    filled += read
                    controller.captureLevel = maxAbs(chunk, read)
                }
            } finally {
                runCatching { record.stop() }
            }

            // 归一化。filled < need 时尾部留静音，够短就不喂进重采样。
            val floats = FloatArray(filled) { i -> pcm[i] / 32768f }
            // 交给上层（引擎→wasm 指纹→端点）前先重采样到 8k；这一步是纯函数，已在单测覆盖
            resampleLinear(floats, InputRateHz, AUDIO_MATCH_SAMPLE_RATE)
        } finally {
            controller.captureLevel = 0f
            runCatching { record.release() }
            // 一个 MediaProjection 实例只能开一次采集会话（Android 14+ 复用直接 SecurityException，
            // 官方文档原话「单次使用」「每次会话前都要重新征得同意」）。所以这轮抓完就把会话作废：
            // ready 翻回 false，下次点按会重新弹授权框 —— 想省掉那次弹框就会换来「点一下就秒失败」。
            controller.endCapture()
        }
    }

    /** 前 [n] 个采样点的绝对值峰值，归一化到 [0,1]。 */
    private fun maxAbs(samples: ShortArray, n: Int): Float {
        var peak = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private companion object {
        const val InputRateHz = 16_000
        const val CaptureGraceMs = 1_500L
    }
}
