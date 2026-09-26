package app.hypochlorite.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.audio.AUDIO_MATCH_SECONDS
import app.hypochlorite.player.AudioMatchPhase
import app.hypochlorite.player.running
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.FourCornerFrame
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.RadarIcon
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors

/** 家族缓动。全 App 十几处用的都是这条，见 Widgets / RoamTransitionOverlay。 */
private val House = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

/**
 * 听歌识曲页 —— 真链路：抓系统音频 → wasm 算指纹 → 打网易比对接口。
 *
 * 页面上只有两样东西：一行签名式标题、一个可点的方块。没有环、没有波形、没有命中卡 ——
 * 识别中唯一的反馈是方块下面那行小字（阶段 + 中止提示），命中则由 [HypochloriteViewModel]
 * 直接接管播放并弹出详情页，这一页根本不参与结果展示。
 *
 * 中间那个方块就是全部操作：没授权就当场把授权要齐（麦克风 → 录屏），要齐了先停掉自家播放
 * 再开链（自家输出也在采集集合里，不停下来录的就是自己）；跑起来了再点一下就中止。
 */
@Composable
internal fun AudioMatchScreen(state: HomeState, vm: HypochloriteViewModel) {
    val am = state.audioMatch
    val colors = LocalHypochloriteColors.current
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val ready = state.audioCaptureReady
    val running = am.phase.running

    LaunchedEffect(Unit) { vm.enterAudioMatch() }

    // 授权链：先 RECORD_AUDIO，拿到后再发录屏授权 Intent。两个 launcher 必须留在本 Composable
    // 里注册 —— 引擎那层刻意不含 Android 依赖才能在 JVM 上单测。doLaunchProjection 是局部函数
    // 又被前一个 launcher 引用，所以用 lateinit 串起来。
    lateinit var doLaunchProjection: () -> Unit
    val recordLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLaunchProjection()
        else vm.audioMatchNote("要先给麦克风权限才抓得到系统声音")
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // 授权一次只够一次会话（见 MediaProjectionCaptureSource），所以签完直接接着跑识别
            vm.submitAudioProjectionResult(result.resultCode, data)
        } else {
            vm.audioMatchNote("没拿到录屏授权，识别没法读别的应用的声音")
        }
    }
    doLaunchProjection = {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) vm.audioMatchNote("本机没有投屏服务，用不了系统音频")
        else runCatching { projectionLauncher.launch(mgr.createScreenCaptureIntent()) }
            .onFailure { vm.audioMatchNote("开不了录屏授权，稍后再试") }
    }

    fun onStageTap() {
        // 运行中没有别的开关，再点一下就当场中止
        if (running) {
            vm.cancelAudioMatch()
            return
        }
        if (!supported) {
            vm.audioMatchNote("本机 Android < 10，抓不到别的应用的声音")
            return
        }
        val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // 投影就绪就整条链都齐了（就绪只能由授权成功后回调得来）。再判一次麦克风，
        // 兜住用户中途跑去系统设置里把它关掉的情况。
        when {
            ready && micGranted -> vm.startAudioMatch()
            micGranted -> doLaunchProjection()
            else -> recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler { vm.back() }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.clickableNoRipple { vm.back() }.padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            // 签名式显示字：超粗大字距拉丁 + 常规字距中文，同 RoamTransitionOverlay 的 ROAM 锁
            BasicText(
                "MATCH",
                style = BodyStyle.copy(
                    color = colors.text, fontWeight = HeavyBold, fontSize = 19.sp, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.width(8.dp))
            BasicText(
                "听歌识曲",
                style = BodyStyle.copy(
                    color = colors.muted, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 1.sp,
                ),
            )
        }
        Hairline()

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MatchTapTarget(onTap = ::onStageTap)
                Spacer(Modifier.height(22.dp))
                MonoText(
                    if (running) phaseLabel(am.phase) else if (ready) "点按开始识别" else "点按授权并识别",
                    bold = true,
                    size = 13,
                )
                MonoText(
                    if (running) "// 再点一下中止" else "// 抓 $AUDIO_MATCH_SECONDS 秒系统声音 · 比对曲库",
                    color = colors.muted.copy(alpha = 0.8f),
                    size = 11,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // 装机包里没带那份私有 wasm 时会静默降级成假指纹，那种包只会「没听出来」且快得多。
                // 这一行是给这种情况留的：不然用户只会觉得「识曲坏了」。
                if (!state.audioMatchFingerprintReal) {
                    MonoText(
                        "// 指纹资源缺失，装包不完整：识别只会「没听出来」",
                        color = colors.muted.copy(alpha = 0.8f),
                        size = 11,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 识别中唯一的文字反馈。阶段名说人话，不用枚举名。 */
private fun phaseLabel(phase: AudioMatchPhase): String = when (phase) {
    AudioMatchPhase.Capturing -> "在听…"
    AudioMatchPhase.Fingerprinting -> "算指纹…"
    AudioMatchPhase.Matching -> "比对中…"
    else -> "识别中…"
}

/**
 * 中心方块：四角框 + 雷达图（那个图标的注释就写着它和同心环同源）。
 *
 * 按压反馈按家规走 alpha + 轻微内缩，不是 ripple —— 全 App 没有 ripple。
 */
@Composable
private fun MatchTapTarget(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalHypochloriteColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        press.animateTo(if (pressed) 0.45f else 1f, tween(if (pressed) 110 else 160, easing = House))
    }
    Box(
        modifier = modifier
            .size(132.dp)
            .graphicsLayer {
                val v = press.value
                alpha = v
                val s = 1f - (1f - v) * 0.07f
                scaleX = s
                scaleY = s
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        FourCornerFrame(
            modifier = Modifier.fillMaxSize(),
            color = colors.text.copy(alpha = 0.55f),
            arm = 10.dp,
            stroke = 1.5.dp,
        ) {}
        RadarIcon(color = colors.text, size = 30.dp)
    }
}
