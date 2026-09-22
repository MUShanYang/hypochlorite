package app.hypochlorite.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlinx.coroutines.delay

/**
 * 一起听的一次性反馈条。
 *
 * 挂根节点而不是房间页 —— 长按推歌的位置是任意歌曲列表，那里离开房间页很远，
 * 反馈必须跟着用户走。视觉沿用等宽 + 方框，和列表行的横幅区分开（横幅扫过，这个是从下浮起）。
 */
@Composable
internal fun ListenToast(text: String?, onDone: () -> Unit) {
    if (text.isNullOrEmpty()) return
    var shown by remember(text) { mutableStateOf(false) }
    LaunchedEffect(text) {
        shown = true
        delay(1800)
        shown = false
        delay(200)
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        MonoText(
            text = text,
            color = LocalHypochloriteColors.current.onBanner,
            size = 15,
            bold = true,
            maxLines = 1,
            modifier = Modifier
                .graphicsLayer {
                    val v = if (shown) 1f else 0f
                    this.alpha = v
                    translationY = (1f - v) * 12.dp.toPx()
                }
                .background(LocalHypochloriteColors.current.banner)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
