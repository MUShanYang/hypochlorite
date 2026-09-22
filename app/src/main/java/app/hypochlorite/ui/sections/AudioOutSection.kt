package app.hypochlorite.ui.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.player.GAIN_MUTE_BELOW
import app.hypochlorite.player.formatSampleRate
import app.hypochlorite.player.gainDbForPercent
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.theme.LocalHypochloriteColors

/**
 * HiFi 音频输出区。
 *
 * 交互上刻意分两层：**顶层四项开关一眼可读**，每项都带一句「什么时候会有用」，
 * 用户不需要懂 Android 音频栈也能判断该不该开；**需要细调的东西收在折叠里**
 * （精确音量），避免把设置页拉成一条长长的参数表。
 *
 * 状态显示遵循「说真话」原则：开关开着但系统拒绝了，界面必须显示为「未生效」，
 * 而不是给一个好看但假的绿灯。
 */
@Composable
internal fun AudioOutSection(state: HomeState, vm: HypochloriteViewModel) {
    val a = state.audioOut

    MonoText("音频输出", modifier = Modifier.padding(top = 28.dp))
    Hairline(Modifier.padding(top = 8.dp))

    // --- USB 独占 ---
    SwitchRow(
        label = "USB 独占",
        on = a.usbExclusive,
        // 没插设备时**不禁用**：点了不是没反应，而是给出「为什么开不了」。
        // 禁用会让用户以为是坏了，这才是最容易被误读成 bug 的状态。
        onClick = { vm.setUsbExclusive(!a.usbExclusive) },
        modifier = Modifier.padding(top = 16.dp),
    )

    // --- 采样率匹配 ---
    ResampleBlock(state)

    // --- 独占音频焦点 ---
    SwitchRow(
        label = "独奏模式",
        on = a.exclusiveFocus,
        onClick = { vm.setExclusiveFocus(!a.exclusiveFocus) },
        modifier = Modifier.padding(top = 18.dp),
    )

    // --- 保持唤醒 ---
    SwitchRow(
        label = "保持唤醒",
        on = a.keepAwake,
        onClick = { vm.setKeepAwake(!a.keepAwake) },
        modifier = Modifier.padding(top = 18.dp),
    )

    // --- 音量控制方式 ---
    MonoText("音量控制", modifier = Modifier.padding(top = 26.dp))
    Hairline(Modifier.padding(top = 8.dp))
    SwitchRow(
        label = "系统级音量直控",
        on = a.directVolume,
        onClick = { vm.setDirectVolume(!a.directVolume) },
        modifier = Modifier.padding(top = 14.dp),
    )

    // 直控开启时滑块失去意义（增益被强制 1.0），直接不显示
    if (!a.directVolume) {
        GainSlider(
            percent = a.gainPercent,
            onChange = { vm.setGain(it) },
            onCommit = { vm.commitGain() },
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    if (state.hifiMsg.isNotEmpty()) {
        MonoText(state.hifiMsg, muted = true, modifier = Modifier.padding(top = 14.dp))
    }
}

/**
 * 采样率匹配状态。
 *
 * 这是整套 HiFi 选项里**唯一一个有真实技术收益**的区块，所以给它独立的视觉层级，
 * 而不是混在开关列表里当一个副标题。
 *
 * 核心逻辑：Android 的 AudioFlinger 只在「音源采样率 == 输出设备原生采样率」时
 * 不做 SRC。所以这里只报三件事 —— 音源多少、设备要多少、现在会不会被重采样。
 *
 * **拿不到数据时什么都不说**。设备没报能力列表、接口没报采样率，都不显示结论，
 * 也不用「可能」「也许」糊过去 —— 宁可不说，也不给一个可能是假的警告。
 */
@Composable
private fun ResampleBlock(state: HomeState) {
    MonoText("采样率匹配", modifier = Modifier.padding(top = 26.dp))
    Hairline(Modifier.padding(top = 8.dp))

    when {
        state.sourceSampleRate == null -> Unit

        state.supportedRates.isEmpty() -> MonoText(
            "音源 ${formatSampleRate(state.sourceSampleRate)}",
            muted = true,
            modifier = Modifier.padding(top = 12.dp),
        )

        state.willResample -> MonoText(
            "${formatSampleRate(state.sourceSampleRate)} → " +
                "${formatSampleRate(state.nativeSampleRate)}  正在转一次",
            modifier = Modifier.padding(top = 12.dp),
        )

        else -> MonoText(
            "${formatSampleRate(state.sourceSampleRate)} → " +
                "${formatSampleRate(state.nativeSampleRate)}  直通",
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 一行开关：左边标题 + 一句说明，右边状态词。整行可点，命中区够大。 */

/**
 * 精确音量滑块。
 *
 * 没有用 Material 的 Slider —— 这个 app 全篇没有 Material 控件，插一个进来的
 * 圆角与阴影会和纯黑等宽语言打架。这里直接自绘：一条 1dp 的刻度线 + 一个
 * 方块游标，和底栏进度条的视觉是同一套。
 *
 * 拖动中只回调 [onChange]（改内存、不落盘），抬手才回调 [onCommit] ——
 * 一路拖过去会触发几十次 prefs 写入，没必要。
 */
@Composable
private fun GainSlider(
    percent: Int,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val db = gainDbForPercent(percent)
    val label = when {
        percent < GAIN_MUTE_BELOW -> "[静音]"
        db > -0.05 -> "[-0.0dB]"
        else -> "[${String.format(java.util.Locale.US, "%.1f", db)}dB]"
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText("精确音量", size = 15)
            MonoText(label, muted = percent < GAIN_MUTE_BELOW, size = 15)
        }
        Spacer(Modifier.height(10.dp))

        var width by remember { mutableIntStateOf(1) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun emit(x: Float) {
                            onChange(((x / width) * 100f).toInt().coerceIn(0, 100))
                        }
                        emit(down.position.x)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            emit(ch.position.x)
                            // 必须消费，否则手势会被外层的 verticalScroll 抢走
                            ch.consume()
                        }
                        onCommit()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val cy = this.size.height / 2f
                val w = this.size.width
                val unit = 1.dp.toPx()
                // 刻度：0dB 位置（93 格）单独标出来，用户才知道哪一格是「不衰减」
                drawLine(
                    color = colors.text.copy(alpha = 0.28f),
                    start = Offset(0f, cy),
                    end = Offset(w, cy),
                    strokeWidth = unit,
                )
                val zeroX = w * (93f / 100f)
                drawLine(
                    color = colors.muted,
                    start = Offset(zeroX, cy - 5.dp.toPx()),
                    end = Offset(zeroX, cy + 5.dp.toPx()),
                    strokeWidth = unit,
                )
                drawLine(
                    color = colors.text,
                    start = Offset(0f, cy),
                    end = Offset(w * (percent / 100f), cy),
                    strokeWidth = 2.dp.toPx(),
                )
                val cx = w * (percent / 100f)
                val half = 4.dp.toPx()
                drawRect(
                    color = colors.text,
                    topLeft = Offset(cx - half, cy - 10.dp.toPx()),
                    size = Size(half * 2, 20.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoText("0", muted = true, size = 13)
            MonoText("93 = 不衰减", muted = true, size = 13)
            MonoText("100", muted = true, size = 13)
        }
    }
}
