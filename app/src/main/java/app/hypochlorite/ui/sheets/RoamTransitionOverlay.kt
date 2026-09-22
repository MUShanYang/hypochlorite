package app.hypochlorite.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.HeavyBold
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlinx.coroutines.delay

@Composable
internal fun RoamTransitionOverlay(
    dir: Int,
    onReadyToPlay: () -> Unit,
    onFinished: () -> Unit,
) {
    BackHandler { /* consume back during transition */ }

    // dir > 0（底栏右滑）：从左边席卷进来，再从右边穿出去。
    // 必须跟手势预览（从左往右铺满）同一边；从右边进来会感觉整段反了。
    val enterBg = if (dir > 0) -1f else 1f
    val exitBg = -enterBg
    val bgOffset = remember(dir) { Animatable(enterBg) }

    DisposableEffect(dir) {
        onDispose {
            onFinished()
        }
    }

    LaunchedEffect(dir) {
        bgOffset.snapTo(enterBg)

        // 1. 背景作为遮罩从屏幕边缘席卷滑入 (280ms)
        bgOffset.animateTo(0f, tween(280, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)))

        // 2. 后台启动切页加载并在中心停留
        onReadyToPlay()
        delay(260)

        // 3. 背景遮罩继续席卷滑出屏幕，切除并带走文字 (260ms)
        bgOffset.animateTo(exitBg, tween(260, easing = FastOutLinearInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        // 背景容器：自身携带 clip = true 作为动态遮罩，背景扫到哪里，哪里才能看到内部文字
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = bgOffset.value * size.width
                    clip = true
                }
                .background(LocalHypochloriteColors.current.banner),
        ) {
            // 文字容器：反向抵消背景自身位移（静态锚定屏幕中央，不跟随时空漂移）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = -bgOffset.value * size.width
                    },
            ) {
                val onBanner = LocalHypochloriteColors.current.onBanner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dir < 0) {
                        BasicText(
                            text = "<<<  ",
                            style = BodyStyle.copy(
                                color = onBanner.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                    BasicText(
                        text = "ROAM",
                        style = BodyStyle.copy(
                            color = onBanner,
                            fontWeight = HeavyBold, // 超粗体
                            fontSize = 44.sp, // 偏大超粗
                            letterSpacing = 5.sp,
                        ),
                    )
                    BasicText(
                        text = "  漫游",
                        style = BodyStyle.copy(
                            color = onBanner,
                            fontWeight = FontWeight.Normal, // 细体
                            fontSize = 16.sp,
                            letterSpacing = 2.sp,
                        ),
                    )
                    if (dir > 0) {
                        BasicText(
                            text = "  >>>",
                            style = BodyStyle.copy(
                                color = onBanner.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp,
                            ),
                        )
                    }
                }
                BasicText(
                    text = "// 心动漫游 · 智能探索",
                    style = BodyStyle.copy(
                        color = onBanner.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Normal, // 细体
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}
