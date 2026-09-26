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
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import app.hypochlorite.netease.Song
import app.hypochlorite.player.AudioMatchPhase
import app.hypochlorite.player.running
import app.hypochlorite.ui.AudioWaveLine
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Cover
import app.hypochlorite.ui.FourCornerFrame
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.NoRingWave
import app.hypochlorite.ui.RadarIcon
import app.hypochlorite.ui.RingField
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 家族缓动。全 App 十几处用的都是这条，见 Widgets / RoamTransitionOverlay。 */
private val House = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

/**
 * 听歌识曲页 —— 真链路：抓系统音频 → wasm 算指纹 → 打网易比对接口。
 *
 * 中间那个方块就是全部操作：没授权就当场把授权要齐（麦克风 → 录屏），要齐了先停掉自家播放
 * 再开链（自家输出也在采集集合里，不停下来录的就是自己）。
 *
 * 识别中的三秒界面上**一个字都没有** —— 同心环加一条吃采集电平的波形就是全部反馈。用户不需要
 * 知道进度，他需要看见「它在听」：静音时那条线几乎躺平，有声才起伏。失败与「没听出来」只走
 * 根层 toast（见 HypochloriteRoot 的 ToastHost）。
 *
 * 命中时三拍：方块让位 → 环从中间往里收、同时一道颜色波向外扫（对向）→ banner 色幕从左铺过来
 * 把答案揭出来，文字原地不动。方块平时也在（运行中点它就是中止），只在命中那一刻让位给卡片。
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

    // 四拍编排的三个驱动量 + 方块/提示的让位。全是 Float，按家规只在 graphicsLayer / 绘制的
    // lambda 里读，逐帧不重组。
    val gather = remember { Animatable(0f) }
    val burst = remember { Animatable(NoRingWave) }
    val wipe = remember { Animatable(-1f) }
    val stageAlpha = remember { Animatable(1f) }
    // 卡片只在 Hit 且有歌时存在；写成可空局部值，卡片处就能直接智能转换，不必 !!
    val shownHit = if (am.phase == AudioMatchPhase.Hit) am.hit else null

    LaunchedEffect(running) {
        if (running) {
            gather.snapTo(0f)
            burst.snapTo(NoRingWave)
            wipe.snapTo(-1f)
            stageAlpha.snapTo(1f)
        }
    }
    // 进页面时引擎可能还停在上一次的 Hit 上（状态挂在 Application 级的引擎里）。enterAudioMatch
    // 收回 Idle 要绕一圈 collector，赶不上首次组合 —— 记下进页时的 seq，只认比它新的那一次，
    // 否则每次重进页面都会把上一首命中重演一遍。
    val enteredSeq = remember { am.hitSeq }
    LaunchedEffect(am.hitSeq) {
        if (am.hitSeq == 0L || am.hitSeq == enteredSeq || am.phase != AudioMatchPhase.Hit) return@LaunchedEffect
        // 方块先让位，把中心腾给收拢和卡片
        stageAlpha.animateTo(0f, tween(120, easing = FastOutLinearInEasing))
        // 颜色波向外、场向内，两件事同时对向发生
        launch {
            burst.animateTo(1f, tween(620, easing = House))
            burst.snapTo(NoRingWave)
        }
        launch { gather.animateTo(1f, tween(420, delayMillis = 90, easing = House)) }
        delay(300)
        // 色幕从左扫进来、铺满即停：它落下之后就是卡片本体
        wipe.animateTo(0f, tween(280, easing = House))
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            RingField(
                active = running,
                modifier = Modifier.align(Alignment.Center).size(360.dp),
                energy = vm::captureLevel,
                ringColor = colors.text,
                ballColor = colors.accent,
                waveColor = colors.accent,
                wave = { burst.value },
                gather = { gather.value },
            )

            // 方块**一直挂着**，靠 stageAlpha 让位：命中那一刻它是淡出退场的，卡片才有地方落。
            // 卡片浮在它上面（Box 后画的子节点在上），所以让位期间点击不会漏到方块上 —— 但
            // 卡片自身不可点，透明的边角会把事件穿下去，故显式禁掉。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = stageAlpha.value },
            ) {
                MatchTapTarget(onTap = ::onStageTap, enabled = shownHit == null)
                if (!running) {
                    Spacer(Modifier.height(22.dp))
                    MonoText(
                        if (ready) "点按开始识别" else "点按授权并识别",
                        bold = true,
                        size = 13,
                    )
                    MonoText(
                        "// 抓 $AUDIO_MATCH_SECONDS 秒系统声音 · 比对曲库",
                        color = colors.muted.copy(alpha = 0.8f),
                        size = 11,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // 替代原先那条「抓到声音 xx%」的读数：同样的信息，从文字换成形态。
            // 只在**真在录**的那 3 秒挂着 —— 算指纹/比对阶段采集电平恒 0，喂它也是直线，
            // 不如让它自己淡掉并收掉帧循环（playing=false 会走完衰减再退出）。
            if (running) {
                AudioWaveLine(
                    playing = am.phase == AudioMatchPhase.Capturing,
                    audioLevel = vm::captureLevel,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    inkColor = colors.text,
                )
            }

            // 最后画，所以浮在方块之上
            if (shownHit != null) {
                MatchHitCard(
                    song = shownHit,
                    offset = { wipe.value * it },
                    onPlay = vm::onAudioMatchHit,
                    onAgain = ::onStageTap,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * 中心方块：四角框 + 雷达图（那个图标的注释就写着它和同心环同源）。
 *
 * 按压反馈按家规走 alpha + 轻微内缩，不是 ripple —— 全 App 没有 ripple。
 */
@Composable
private fun MatchTapTarget(onTap: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
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
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onTap),
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

/**
 * 命中卡：色幕从左扫进来揭示内容，文字不跟着漂移（反平移），扫完就地停住成为卡片本体。
 *
 * 卡上文字一律不传 `muted = true` —— MonoText 里 muted 抢在 color 之前解析，
 * 传了在 banner 实底上会得一条低对比灰字。
 */
@Composable
private fun MatchHitCard(
    song: Song,
    offset: (Float) -> Float,
    onPlay: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHypochloriteColors.current
    val ink = colors.bannerInk
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            // 移动的那一层：clip 到自身边界，于是右边缘扫到哪、内容才露到哪
            .graphicsLayer { clip = true; translationX = offset(size.width.toFloat()) }
            .background(colors.banner),
    ) {
        // 被夹在中间的内容反向抵消父层位移，因此它是「原地被揭出来」而非跟着滑
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = -offset(size.width.toFloat()) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp)) {
                    Cover(song.cover, Modifier.size(56.dp))
                    FourCornerFrame(
                        modifier = Modifier.fillMaxSize(),
                        color = ink.copy(alpha = 0.5f),
                        arm = 8.dp,
                        stroke = 1.25.dp,
                    ) {}
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            "MATCHED",
                            style = BodyStyle.copy(
                                color = ink, fontWeight = HeavyBold, fontSize = 17.sp, letterSpacing = 3.5.sp,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            "听到了",
                            style = BodyStyle.copy(
                                color = ink.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                            ),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    MonoText(song.name, color = ink, bold = true, size = 16, maxLines = 1, marquee = true)
                    val by = song.artists.joinToString(" / ")
                    if (by.isNotEmpty()) {
                        MonoText(by, color = ink.copy(alpha = 0.72f), size = 12, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoverBold("播放这首", onClick = onPlay, color = ink, size = 14)
                HoverBold("再听一次", onClick = onAgain, color = ink.copy(alpha = 0.7f), size = 14)
            }
        }
    }
}
