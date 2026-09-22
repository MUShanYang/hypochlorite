package app.hypochlorite.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.Song
import app.hypochlorite.ui.QueueUi
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.MiniIconButton
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.SongRowHeight
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放列表（队列管理）面板。
 *
 * 从详情页控制行的队列图标唤起：从底部滑上来、盖在详情页上，收掉就回到详情页。
 * 两个页签 —— 队列（点按跳转 / 删除 / 长按拖动排序 / 清空）和历史（最近播过的 200 首）。
 *
 * 队列行的「正在播放」永远高亮，顺序变了（拖动 / 删除）跟手即时反映 ——
 * 数据源就是 [PlayerSnapshot.queue]，没有任何本地副本可失步。
 */
@Composable
internal fun PlayQueuePanel(player: QueueUi, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val scope = rememberCoroutineScope()
    val slideY = remember { Animatable(screenHeightPx) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        slideY.animateTo(
            targetValue = 0f,
            animationSpec = tween(300, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1.0f)),
        )
    }

    fun close() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            slideY.animateTo(
                targetValue = screenHeightPx,
                animationSpec = tween(220, easing = FastOutLinearInEasing),
            )
            vm.closeQueue()
        }
    }

    // 面板在详情页之后组合 → 这里的 BackHandler 优先吃掉返回，先收面板再收详情页
    BackHandler(enabled = !isClosing) { close() }

    var tab by remember { mutableIntStateOf(0) }   // 0 = 队列，1 = 历史
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(2600)
            confirmClear = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.4f))
            .clickableNoRipple { close() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .graphicsLayer { translationY = slideY.value }
                .background(colors.background)
                .clickableNoRipple { /* 挡住穿透，别把点按漏给遮罩 */ },
        ) {
            // ---- 顶栏：返回 + 标题 + 数量 + 清空 ----
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clickableNoRipple { close() }
                        .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackArrowIcon(size = 18.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MonoText("播放列表", bold = true, size = 19, maxLines = 1)
                    MonoText(
                        when (tab) {
                            0 -> "${player.queue.size} 首在队列"
                            else -> "最近 ${player.history.size} 首"
                        },
                        muted = true,
                        size = 12,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (tab == 0 && player.queue.isNotEmpty()) {
                    MonoText(
                        text = if (confirmClear) "确认清空?" else "清空",
                        size = 13,
                        color = colors.warning,
                        modifier = Modifier
                            .clickableNoRipple {
                                if (confirmClear) {
                                    vm.queueClear()
                                    confirmClear = false
                                } else {
                                    confirmClear = true
                                }
                            }
                            .padding(8.dp),
                    )
                }
            }
            Hairline(Modifier.padding(horizontal = 14.dp))

            // ---- 页签：队列 / 历史 ----
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                QueueTab("队列", tab == 0) { tab = 0 }
                Spacer(Modifier.width(22.dp))
                QueueTab("历史", tab == 1) { tab = 1 }
            }
            Hairline(Modifier.padding(horizontal = 14.dp))

            when (tab) {
                0 -> QueueTabList(player, vm)
                else -> HistoryTabList(player, vm)
            }
        }
    }
}

@Composable
private fun QueueTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalHypochloriteColors.current
    Column(Modifier.clickableNoRipple(onClick)) {
        MonoText(
            text = label,
            bold = selected,
            size = 15,
            color = if (selected) colors.text else colors.muted,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Box(
            Modifier
                .width(22.dp)
                .height(2.dp)
                .background(if (selected) colors.text else Color.Transparent),
        )
    }
}

/**
 * 队列页签。行高定死 [SongRowHeight]，长按拖动换位：
 * 手指划过多少行就实时和谁交换（拖动计数每次整行跳变时立刻 commit），
 * 松手即定。拖动中的那一行浮起来（投影 + 跟手位移）。
 */
@Composable
private fun ColumnScope.QueueTabList(player: QueueUi, vm: HypochloriteViewModel) {
    val density = LocalDensity.current
    val rowHpx = with(density) { SongRowHeight.toPx() }
    val queue = player.queue
    val listState = rememberLazyListState()

    var dragIdx by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    if (queue.isEmpty()) {
        MonoText("队列是空的", muted = true, modifier = Modifier.padding(start = 14.dp, top = 18.dp))
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(queue.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        if (queue.isEmpty()) return@detectDragGesturesAfterLongPress
                        val scrolled = listState.firstVisibleItemIndex * rowHpx +
                            listState.firstVisibleItemScrollOffset
                        dragIdx = ((scrolled + off.y) / rowHpx).toInt().coerceIn(0, queue.lastIndex)
                        dragY = 0f
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val from = dragIdx ?: return@detectDragGesturesAfterLongPress
                        dragY += drag.y
                        val shift = (dragY / rowHpx).toInt()
                        if (shift != 0) {
                            val to = (from + shift).coerceIn(0, queue.lastIndex)
                            if (to != from) {
                                vm.queueMove(from, to)
                                dragIdx = to
                                dragY -= shift * rowHpx
                            }
                        }
                    },
                    onDragEnd = { dragIdx = null; dragY = 0f },
                    onDragCancel = { dragIdx = null; dragY = 0f },
                )
            },
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(queue, key = { i, s -> "q-${s.id}-$i" }) { i, song ->
            val isCurrent = i == player.index
            Box(
                Modifier
                    .height(SongRowHeight)
                    .fillMaxWidth()
                    .zIndex(if (dragIdx == i) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragIdx == i) dragY else 0f
                        shadowElevation = if (dragIdx == i) 24f else 0f
                    },
            ) {
                QueueRow(
                    song = song,
                    index = i,
                    isCurrent = isCurrent,
                    onClick = { vm.queueJumpAt(i) },
                    onRemove = { vm.queueRemoveAt(i) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalHypochloriteColors.current
    Row(
        Modifier
            .fillMaxSize()
            .background(if (isCurrent) colors.text.copy(alpha = 0.06f) else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = if (isCurrent) "NOW" else "%02d".format(index + 1),
            size = 12,
            color = if (isCurrent) colors.accent else colors.muted,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            MonoText(
                text = song.name,
                bold = isCurrent,
                size = if (isCurrent) 16 else 15,
                maxLines = 1,
                marquee = isCurrent,
                color = if (isCurrent) colors.text else colors.text.copy(alpha = 0.82f),
            )
            MonoText(
                text = song.artists.joinToString(" / "),
                muted = true,
                size = 12,
                maxLines = 1,
            )
        }
        MiniIconButton(onClick = onRemove) {
            // 一条斜杠加一条反向斜杠的小 ×
            Canvas(Modifier.size(12.dp)) {
                val sw = 1.6.dp.toPx()
                drawLine(colors.muted, Offset(0f, 0f), Offset(size.width, size.height), sw, StrokeCap.Square)
                drawLine(colors.muted, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Square)
            }
        }
    }
}

/** 历史页签：最近播过的歌，点一下加回队列并播放。 */
@Composable
private fun ColumnScope.HistoryTabList(player: QueueUi, vm: HypochloriteViewModel) {
    val history = player.history
    if (history.isEmpty()) {
        MonoText("还没有播放历史", muted = true, modifier = Modifier.padding(start = 14.dp, top = 18.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(history.asReversed(), key = { i, s -> "h-${s.id}-$i" }) { _, song ->
            Row(
                Modifier
                    .height(SongRowHeight)
                    .fillMaxWidth()
                    .clickableNoRipple { vm.playSong(song) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MonoText(song.name, size = 15, maxLines = 1)
                    MonoText(song.artists.joinToString(" / "), muted = true, size = 12, maxLines = 1)
                }
            }
        }
    }
}
