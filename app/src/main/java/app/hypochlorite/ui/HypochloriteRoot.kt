package app.hypochlorite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.Route
import app.hypochlorite.ThemeReveal
import app.hypochlorite.ui.screens.AlbumScreen
import app.hypochlorite.ui.screens.ArtistScreen
import app.hypochlorite.ui.screens.ConfigScreen
import app.hypochlorite.ui.screens.HomeScreen
import app.hypochlorite.ui.screens.ListenTogetherScreen
import app.hypochlorite.ui.screens.LoginScreen
import app.hypochlorite.ui.screens.NowPlayingScreen
import app.hypochlorite.ui.screens.PlaylistScreen
import app.hypochlorite.ui.sheets.ListenToast
import app.hypochlorite.ui.sheets.LoginVerifyOverlay
import app.hypochlorite.ui.sheets.PlayQueuePanel
import app.hypochlorite.ui.sheets.RoamTransitionOverlay
import app.hypochlorite.ui.theme.HypochloriteTheme
import app.hypochlorite.ui.theme.MonetPalette

@Composable
fun HypochloriteRoot(vm: HypochloriteViewModel) {
    // 整棵界面订同一份 HomeState，但每个宿主只读自己的切片。
    // 搜索打字、登录倒计时不会把取色底、详情页、队列面板一起重组。
    val uiState = vm.ui.collectAsStateWithLifecycle()
    val shell by remember(uiState) {
        derivedStateOf {
            val s = uiState.value
            ShellChrome(s.palette, s.reveal, s.monetEnabled, s.backdropCoverUrl)
        }
    }

    // 「跟随系统」要跟着系统深色开关实时变。Manifest 里已经把 uiMode 声明为自行处理
    // （不重建 Activity），这里再盯一眼 Compose 侧的 Configuration：切一次重算一次。
    // 取色模式下这一步是幂等的 —— 配置没变时算出来的 palette 与现有一致，不会触发扩散。
    val uiMode = LocalConfiguration.current.uiMode
    LaunchedEffect(uiMode) { vm.onConfigurationChanged() }

    HypochloriteTheme(shell.palette) {
        MonetBackground(
            palette = shell.palette,
            reveal = shell.reveal,
            coverUrl = if (shell.monetEnabled) shell.backdropCoverUrl else null,
            onRevealFinished = vm::finishReveal,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                RouteHost(uiState, vm)
                NowPlayingHost(uiState, vm)
                QueueHost(uiState, vm)
                VerifyHost(uiState, vm)
                RoamHost(uiState, vm)
                // 一起听的操作反馈。列表页点按/长按推歌都可能发生在任意页，房间页看不到 —— 所以挂根节点。
                ToastHost(uiState, vm)
            }
        }
    }
}

private data class ShellChrome(
    val palette: MonetPalette,
    val reveal: ThemeReveal?,
    val monetEnabled: Boolean,
    val backdropCoverUrl: String?,
)

@Composable
private fun RouteHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    // 只订路由。页面自己再读 uiState，搜索打字不会把 AnimatedContent 整段判成「没变」而停在旧画面。
    val route by remember(uiState) { derivedStateOf { uiState.value.route } }
    AnimatedContent(
        targetState = route,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            // 这一层只管**真正的路由切换**（歌单 / 歌手 / 专辑 / 登录 / 设置 / 一起听）。
            // 详情页不是路由：它是 `nowPlayingOpen` 驱动的覆盖层（见 `NowPlayingHost`），
            // 所以「详情页开场 / 收场」永远不会走到这里 —— 想调详情页的方向别来这里改，白改。
            (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 10 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { -it / 12 })
        },
        contentKey = {
            when (it) {
                is Route.PlaylistSongs -> "pl-${it.playlist.id}"
                is Route.ArtistDetail -> "artist-${it.artistName}"
                is Route.AlbumDetail -> "album-${it.albumName}-${it.albumId}"
                else -> it::class.simpleName
            }
        },
        label = "route",
    ) { current ->
        RoutePage(current, uiState, vm)
    }
}

@Composable
private fun RoutePage(route: Route, uiState: State<HomeState>, vm: HypochloriteViewModel) {
    if (route == Route.Home || route == Route.NowPlaying || route == Route.Roam) {
        HomeScreen(uiState, vm)
        return
    }
    val state = uiState.value
    when (route) {
        Route.Home -> Unit
        is Route.PlaylistSongs -> PlaylistScreen(route.playlist, state, vm)
        is Route.ArtistDetail -> ArtistScreen(route.artistName, state, vm)
        is Route.AlbumDetail -> AlbumScreen(route.albumName, route.albumId, state, vm)
        Route.Login -> LoginScreen(state, vm)
        Route.Config -> ConfigScreen(state, vm)
        Route.ListenTogether -> ListenTogetherScreen(state, vm)
        Route.NowPlaying, Route.Roam -> Unit
    }
}

@Composable
private fun NowPlayingHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val open by remember(uiState) { derivedStateOf { uiState.value.nowPlayingOpen } }
    if (!open) return
    val state by remember(uiState) { uiState.slice(HomeState::nowPlayingUi) }
    NowPlayingScreen(state, vm)
}

@Composable
private fun QueueHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val open by remember(uiState) { derivedStateOf { uiState.value.queueOpen } }
    if (!open) return
    // 播放列表（队列管理）：盖在详情页之上 —— 从详情页的控制行点队列图标唤起，
    // 详情页留在原地，收掉面板就回到详情页（网易云的层级关系）
    val queue by remember(uiState) { uiState.slice(HomeState::queueUi) }
    PlayQueuePanel(queue, vm)
}

@Composable
private fun VerifyHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val gate by remember(uiState) {
        derivedStateOf { uiState.value.showVerify to uiState.value.loginVerifyUrl }
    }
    val (show, url) = gate
    // 云盾验证页必须内嵌：它的验证结果是以 cookie 形式落在「那个浏览器」里的，
    // 甩给系统浏览器就等于把结果丢在门外（见 HypochloriteViewModel.completeLoginVerify）。
    if (show && url.isNotEmpty()) LoginVerifyOverlay(url, vm)
}

@Composable
private fun RoamHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val dir by remember(uiState) { derivedStateOf { uiState.value.roamTransitionDir } }
    if (dir != 0) {
        RoamTransitionOverlay(
            dir = dir,
            onReadyToPlay = vm::enterRoamNow,
            onFinished = vm::finishRoamTransition,
        )
    }
}

@Composable
private fun ToastHost(uiState: State<HomeState>, vm: HypochloriteViewModel) {
    val toast by remember(uiState) { derivedStateOf { uiState.value.listen.toast } }
    ListenToast(toast, vm::clearListenToast)
}
