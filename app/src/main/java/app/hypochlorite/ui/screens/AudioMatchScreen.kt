package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.player.AudioMatchPhase
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.LocalHypochloriteColors

/**
 * 听歌识曲页 —— 步骤 2 的骨架。
 *
 * 中心一个方块，点下去跑一遍识别链路，方块上直接写当前阶段，方便肉眼确认
 * Capturing → Fingerprinting → Matching → Hit/NoResult 的时序走通。同心环动画
 * 在下一步（ui/RingField）接进来，这里先留白。
 */
@Composable
internal fun AudioMatchScreen(state: HomeState, vm: HypochloriteViewModel) {
    val am = state.audioMatch
    val colors = LocalHypochloriteColors.current

    // 进页面若还挂着上次结果，先收回空闲
    LaunchedEffect(Unit) { vm.enterAudioMatch() }

    // 每次命中（hitSeq 递增）交接一次：播放 + toast。hitSeq>0 才动，避免首帧误触发
    LaunchedEffect(am.hitSeq) {
        if (am.hitSeq > 0 && am.phase == AudioMatchPhase.Hit) vm.onAudioMatchHit()
    }

    BackHandler { vm.back() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("听歌识曲")
        }
        Hairline()

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val label = phaseLabel(am.phase)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(168.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.5.dp, colors.text.copy(alpha = 0.5f)), CircleShape)
                    .clickableNoRipple {
                        if (am.phase == AudioMatchPhase.Idle || am.phase == AudioMatchPhase.Hit ||
                            am.phase == AudioMatchPhase.NoResult || am.phase == AudioMatchPhase.Error
                        ) vm.startAudioMatch()
                    },
            ) {
                MonoText(label, bold = true, size = 20)
                Spacer(Modifier.height(8.dp))
                if (am.phase == AudioMatchPhase.Hit) {
                    MonoText(am.hit?.line().orEmpty(), muted = true, size = 13, maxLines = 2)
                } else if (am.phase == AudioMatchPhase.Error) {
                    MonoText(am.error.orEmpty(), muted = true, size = 13)
                } else {
                    MonoText("点按开始识别", muted = true, size = 13)
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
