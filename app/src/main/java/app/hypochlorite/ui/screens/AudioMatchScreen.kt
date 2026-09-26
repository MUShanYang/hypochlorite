package app.hypochlorite.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.player.AudioMatchPhase
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.RingField
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.sections.SwitchRow
import app.hypochlorite.ui.theme.LocalHypochloriteColors

/**
 * 听歌识曲页 —— UI 链路骨架 + 真·系统音频抓取（步骤 A）。
 *
 * 中心一个方块，点下去跑一遍识别链路，方块上写当前阶段；同心环动画随后接（步骤 3）。
 * 「抓系统音频」开关切抓取源：关 = 假正弦源（debug 用 forceHitForDebug 走固定命中看交接）；
 * 开 = MediaProjection 真抓别的 App 的声音。因为指纹还是假的，真抓也不会识别出歌，
 * 所以这里额外把采集峰值显示出来 —— 那是「到底有没有听到」唯一的可验证信号。
 */
@Composable
internal fun AudioMatchScreen(state: HomeState, vm: HypochloriteViewModel) {
    val am = state.audioMatch
    val colors = LocalHypochloriteColors.current
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val systemOn = state.audioSystemCapture
    val ready = state.audioCaptureReady

    LaunchedEffect(Unit) { vm.enterAudioMatch() }
    LaunchedEffect(am.hitSeq) {
        if (am.hitSeq > 0 && am.phase == AudioMatchPhase.Hit) vm.onAudioMatchHit()
    }

    // 授权链：先 RECORD_AUDIO，拿到后再发录屏授权 Intent（每次识别会话都要重新取，见控制器注释）。
    // 录屏 Intent 必须用本 Composable 注册的 launcher 直接发起，所以 doLaunchProjection 是局部函数。
    lateinit var doLaunchProjection: () -> Unit
    val recordLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLaunchProjection()
        else vm.audioMatchNote("要先给麦克风权限才能抓系统音频")
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            vm.submitAudioProjectionResult(result.resultCode, data)
        } else {
            vm.setAudioSystemCapture(false)
            vm.audioMatchNote("没拿到录屏授权，回到演示模式")
        }
    }
    doLaunchProjection = {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) vm.audioMatchNote("本机没有投屏服务，用不了系统音频")
        else runCatching { projectionLauncher.launch(mgr.createScreenCaptureIntent()) }
            .onFailure { vm.audioMatchNote("开不了录屏授权，稍后再试") }
    }

    fun enableSystemCapture() {
        vm.setAudioSystemCapture(true)
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) doLaunchProjection() else recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
            Spacer(Modifier.width(8.dp))
            MonoText("听歌识曲")
        }
        Hairline()

        // 抓取源开关
        SwitchRow(
            label = "抓系统音频",
            sub = when {
                !supported -> "本机 Android < 10，不支持，只能演示模式"
                systemOn && ready -> "已就绪：识别时会读取其它 App 播放的声音"
                systemOn && !ready -> "等待录屏授权…"
                else -> "关：用演示音源（假数据，走通链路用）"
            },
            on = systemOn,
            enabled = supported,
            onClick = {
                if (systemOn) vm.setAudioSystemCapture(false) else enableSystemCapture()
            },
            modifier = Modifier.padding(top = 12.dp),
        )

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val label = phaseLabel(am.phase)
            val canStart = am.phase == AudioMatchPhase.Idle || am.phase == AudioMatchPhase.Hit ||
                am.phase == AudioMatchPhase.NoResult || am.phase == AudioMatchPhase.Error
            val running = am.phase == AudioMatchPhase.Capturing ||
                am.phase == AudioMatchPhase.Fingerprinting || am.phase == AudioMatchPhase.Matching
            // 同心环画在方块底下：待命时一颗呼吸环，识别中环向外扩、球在环上转
            RingField(
                active = running,
                modifier = Modifier.size(360.dp),
                energy = vm::audioLevel,
                ringColor = colors.text,
                ballColor = colors.accent,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.5.dp, colors.text.copy(alpha = 0.5f)), CircleShape)
                    .clickableNoRipple {
                        if (!canStart) return@clickableNoRipple
                        // 系统模式还没就绪就再发一次授权；就绪了才真的开始识别
                        if (systemOn && !ready) enableSystemCapture() else vm.startAudioMatch()
                    },
            ) {
                MonoText(label, bold = true, size = 20)
                Spacer(Modifier.height(10.dp))
                when (am.phase) {
                    AudioMatchPhase.Hit -> MonoText(am.hit?.line().orEmpty(), muted = true, size = 13, maxLines = 2)
                    AudioMatchPhase.Error -> MonoText(am.error.orEmpty(), muted = true, size = 13)
                    else -> MonoText(if (systemOn && !ready) "点按授权并识别" else "点按开始识别", muted = true, size = 13)
                }
                // 采集峰值读数：证明真抓到了声音（假源恒接近满值；真源静音时接近 0）
                if (am.phase == AudioMatchPhase.Capturing || am.capturedPeak > 0f) {
                    val pct = (am.capturedPeak * 100).toInt().coerceIn(0, 100)
                    MonoText("抓到声音 $pct%", muted = true, size = 12, modifier = Modifier.padding(top = 8.dp))
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .width(120.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .border(BorderStroke(1.dp, colors.text.copy(alpha = 0.3f)), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(am.capturedPeak.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

private fun phaseLabel(phase: AudioMatchPhase): String = when (phase) {
    AudioMatchPhase.Idle -> "识曲"
    AudioMatchPhase.Capturing -> "聆听中…"
    AudioMatchPhase.Fingerprinting -> "提取指纹…"
    AudioMatchPhase.Matching -> "比对曲库…"
    AudioMatchPhase.Hit -> "听到了"
    AudioMatchPhase.NoResult -> "没听出来"
    AudioMatchPhase.Error -> "识别失败"
}
