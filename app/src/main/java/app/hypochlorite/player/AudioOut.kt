package app.hypochlorite.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * HiFi 音频输出层。
 *
 * 这一层管的是「声音怎么出机器」，和音质偏好（拉哪一档码流）是两件事：
 * 后者决定**取到什么文件**，这里决定**取到之后怎么送进 DAC**。
 *
 * 诚实边界（写进 UI 文案里，不糊弄用户）：
 * Android 的音频栈在 app 之下还有 AudioFlinger / HAL，轨道的位深与采样率由
 * 音频数据和设备能力决定，**app 改不了**。所以这里不承诺「提升音质」，
 * 承诺的是「少一层干扰」——绕开系统混音与重采样、少一次数字衰减、
 * 少一次设备切换带来的重新起流（也就是换设备那一下的爆音）。
 *
 * 所有开关都是「尽力而为」：系统有权拒绝（例如设备不支持独占），
 * 拒绝时退回音质不受影响的普通模式，不报错、不弹窗。
 */

// ---------------------------------------------------------------- 增益换算

/**
 * 把 0..100 的「格」换算成数字增益。
 *
 * 曲线刻意做成**前置衰减**：93 格 = 1.0（0dB），到 50 格时已经 −7.2dB。
 * 理由是整数音量在多数播放器上按 dB 均匀分格，前面几格几乎听不出差别，
 * 真正需要精细调节的是接近满刻度的那一段。
 *
 * `stepsPerDb = 7` 是有意为之：一格 ≈ 0.143dB，刚好在人耳可闻门槛附近，
 * 调一格能听出变化，又不会觉得跨度太大。
 *
 * 人耳感知的「安静」不代表输出为零 —— 拖到 1 格就彻底没声会让人以为坏了。
 * 低于 [GAIN_MUTE_BELOW] 时按静音处理，UI 上也会把它标成 [静音]。
 */
const val GAIN_MUTE_BELOW = 1

fun gainForPercent(percent: Int): Float {
    val p = percent.coerceIn(0, 100)
    if (p < GAIN_MUTE_BELOW) return 0f
    if (p >= 93) return 1f
    return Math.pow(10.0, (p - 93) / (7.0 * 20.0)).toFloat()
}

/** 当前增益对应的 dB 数（负数），UI 直接显示。静音是 −∞，调用方不要拿它去 format。 */
fun gainDbForPercent(percent: Int): Double {
    val g = gainForPercent(percent)
    if (g <= 0f) return Double.NEGATIVE_INFINITY
    return 20.0 * Math.log10(g.toDouble())
}

// ---------------------------------------------------------------- 设备

/** `AudioDeviceInfo.getId()` 是系统全局单调递增的，同一台设备插回来 id 不变 */
typealias UsbDeviceId = Int

/** 系统把 USB Audio Class 报成这三种 type 之一，视厂商实现而定 */
private val USB_TYPES = intArrayOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

fun AudioDeviceInfo.isUsb(): Boolean = type in USB_TYPES

/** 只有能出声的设备才值得列出来（sink），输入设备一律过滤掉 */
fun audioOutputs(ctx: Context): List<AudioDeviceInfo> = runCatching {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.toList()
}.getOrDefault(emptyList())

/**
 * 这台设备**有没有** USB 音频输出能力。
 *
 * 和「当前有没有插 USB 解码器」是两回事，但对用户的含义完全不同：
 * - 「支持但没插」→ 插上就能用，值得让用户去插；
 * - 「不支持」→ 这台手机/平板根本没有 USB 音频宿主，插了也认不出来，
 *   该直接告诉用户别等了，而不是给一句含糊的「没检测到设备」。
 *
 * 判据是系统有没有报出 USB host 这个 feature。注意它只说明硬件具备，
 * 少数机型 USB 音频走的是内置 codec 的特殊路径，可能报 false 却仍能出声 ——
 * 所以这个判断只用于**文案**，不用来禁用开关。
 */
fun hasUsbAudioHost(ctx: Context): Boolean = runCatching {
    ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)
}.getOrDefault(false)

/**
 * 找到「当前该用哪只 USB 设备」。
 *
 * 优先用 [remembered]（用户上次选过的那只），它不在了再退回第一只 USB 设备。
 * 这样拔插一次之后不需要重新选。
 */
fun pickUsbDevice(devices: List<AudioDeviceInfo>, remembered: UsbDeviceId): AudioDeviceInfo? {
    val usb = devices.filter { it.isUsb() }
    if (usb.isEmpty()) return null
    return usb.firstOrNull { it.id == remembered } ?: usb.first()
}

/** 设备名可能很长（「USB Audio Device (2-1.4)」），设置页只留前一段 */
fun AudioDeviceInfo.shortName(): String {
    val raw = productName?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return "USB 音频设备"
    return raw.substringBefore(" (").take(26)
}

/** 「44.1kHz」这种给人看的写法 */
fun formatSampleRate(hz: Int?): String {
    if (hz == null || hz <= 0) return "未知"
    val k = hz / 1000.0
    return if (k == k.toInt().toDouble()) "${k.toInt()}kHz" else "%.1fkHz".format(k)
}

// ---------------------------------------------------------------- 重采样判断

/**
 * 输出设备在 [sampleRate] 这个采样率上能不能**不重采样**直接吃。
 *
 * 这是整套方案的核心依据。Android 的 AudioFlinger 在「音频数据采样率 == 输出设备
 * 原生采样率」时不做 SRC；一旦不等（比如 44.1k 的音源送到只认 48k 的设备），
 * 系统会插一次重采样。所以判断只需比对这两个数。
 *
 * `getSampleRates()` 返回空数组时**不能当成不支持** —— 不少 USB 解码器
 * 不报这个字段，那是设备偷懒而不是能力缺失。这种情况按支持处理，
 * 免得把本来能直通的设备误判成要走重采样。
 */
fun supportsNativeRate(device: AudioDeviceInfo, sampleRate: Int?): Boolean {
    if (sampleRate == null || sampleRate <= 0) return false
    val rates = runCatching { device.sampleRates }.getOrNull() ?: return true
    if (rates.isEmpty()) return true
    return rates.contains(sampleRate)
}

/**
 * 一次「会不会被重采样」的判定结果。
 *
 * [nativeRate] 是设备当前认定的原生采样率；拿不到就是 null，
 * 此时 [willResample] 一律为 false（**宁可不报，也不要给一个可能是假的警告**）。
 */
data class ResampleVerdict(
    val sourceRate: Int?,
    val nativeRate: Int?,
    val willResample: Boolean,
    /** 设备明确支持的采样率列表，给界面展示用 */
    val supportedRates: List<Int> = emptyList(),
) {
    val known: Boolean get() = sourceRate != null && nativeRate != null
}

/**
 * 判断当前这条流送到这台设备上会不会被系统重采样。
 *
 * 三条口径：
 * 1. 设备支持这条流的采样率 → 不会重采样（这是我们要的状态）。
 * 2. 设备报了支持列表但不含这条流的采样率 → **会**重采样，如实告知。
 * 3. 设备没报支持列表（空数组）→ 按「可能重采样」处理但**不显示警告**，
 *    因为拿不到证据。宁可少报，也不能给用户一个假的警报。
 */
fun judgeResample(device: AudioDeviceInfo?, sourceRate: Int?): ResampleVerdict {
    if (device == null) return ResampleVerdict(sourceRate, null, false)
    val rates = runCatching { device.sampleRates }.getOrNull()?.toList().orEmpty()
    val native = nativeSampleRate(device)
    if (sourceRate == null || sourceRate <= 0) {
        return ResampleVerdict(null, native, false, rates)
    }
    if (rates.isEmpty()) {
        // 设备不报能力列表：不知道，就不吓唬用户
        return ResampleVerdict(sourceRate, native, false, emptyList())
    }
    val ok = rates.contains(sourceRate)
    return ResampleVerdict(sourceRate, native, !ok, rates)
}

/**
 * 设备的「原生采样率」—— 也就是系统当前会往它身上送的采样率。
 *
 * 没有直接查询「当前输出采样率」的公开 API，所以按优先级推：
 * 1. **设备自身正在用的采样率**（`AudioTrack.getSampleRate()` 拿到的是我们请求的值，
 *    不是设备值，不能用）。这里退一步：优先选列表里的 48k（Android 的默认源率），
 *    没有就取列表最大值 —— 高解析设备报的最小值往往是它给通话留的兼容档。
 * 2. 列表为空 → 用系统属性 `ro.soc.model` 猜不着，只能给 null。
 *
 * 这个值只用于**展示和比较**，不参与任何格式设置 —— 我们从不改音频格式，
 * 只是把「会不会重采样」这件事说清楚。
 */
fun nativeSampleRate(device: AudioDeviceInfo): Int? {
    val rates = runCatching { device.sampleRates }.getOrNull()?.toList().orEmpty()
    if (rates.isEmpty()) return null
    return rates.firstOrNull { it == 48_000 } ?: rates.maxOrNull()
}

// ---------------------------------------------------------------- 设备热插拔

/**
 * 插入/拔出 USB 音频设备时通知订阅者。
 *
 * 系统**没有**给「USB 音频设备变化」单独的动作，只有 `ACTION_USB_DEVICE_ATTACHED`
 * 这类 USB 主机事件，而它不一定覆盖所有 USB 声卡（部分走的是内置 codec 的
 * USB 路径）。所以这里不注册广播，改用「合成新设备列表、和上一次比对」的方式 ——
 * 由调用方在拿到新列表时调 [observe]，只有 id 集合真的变了才回调。
 *
 * 好处是不依赖任何广播、不会有漏注册/重复注册的问题；代价是得有人主动喂它，
 * 而这个人本来就存在（[devices] 每次读都会刷新列表）。
 */
object DeviceHub {
    private var lastIds: List<UsbDeviceId> = emptyList()

    /** 返回 true 表示这次调用发现 USB 设备集合变了（需要重新绑定输出） */
    fun observe(devices: List<AudioDeviceInfo>): Boolean {
        val ids = devices.filter { it.isUsb() }.map { it.id }.sorted()
        if (ids == lastIds) return false
        lastIds = ids
        return true
    }

    /** 换设备/关开关时清掉记忆，下次 [observe] 一定会报一次变化 */
    fun reset() {
        lastIds = emptyList()
    }

    /**
     * 新设备集合里还找得到 [deviceId] 吗。
     *
     * 拔掉设备后系统会把它的 id 从列表里拿走，这正是判断「用户选的那只还在不在」
     * 的依据 —— 不在就说明拔了，要退回默认输出。
     */
    fun stillPresent(devices: List<AudioDeviceInfo>, deviceId: UsbDeviceId): Boolean =
        rememberedUsbStillPresent(deviceId, devices.filter { it.isUsb() }.map { it.id })
}

/**
 * 记住的那只 USB 设备现在还在不在。
 *
 * [rememberedId] < 0 表示还没记住具体哪一只：只要现在有任意 USB 输出，就不算「拔了」。
 */
fun rememberedUsbStillPresent(rememberedId: UsbDeviceId, presentUsbIds: Collection<UsbDeviceId>): Boolean {
    if (rememberedId < 0) return presentUsbIds.isNotEmpty()
    return rememberedId in presentUsbIds
}
