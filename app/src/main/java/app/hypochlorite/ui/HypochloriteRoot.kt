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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.Route
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

@Composable
fun HypochloriteRoot(vm: HypochloriteViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()

    // 「跟随系统」要跟着系统深色开关实时变。Manifest 里已经把 uiMode 声明为自行处理
    // （不重建 Activity），这里再盯一眼 Compose 侧的 Configuration：切一次重算一次。
    // 取色模式下这一步是幂等的 —— 配置没变时算出来的 palette 与现有一致，不会触发扩散。
    val uiMode = LocalConfiguration.current.uiMode
    LaunchedEffect(uiMode) { vm.onConfigurationChanged() }

    HypochloriteTheme(state.palette) {
        MonetBackground(
            palette = state.palette,
            reveal = state.reveal,
            coverUrl = if (state.monetEnabled) state.backdropCoverUrl else null,
            onRevealFinished = { id -> vm.finishReveal(id) },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                AnimatedContent(
                    targetState = state.route,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        // 这一层只管**真正的路由切换**（歌单 / 歌手 / 专辑 / 登录 / 设置 / 一起听）。
                        // 详情页不是路由：它是 `state.nowPlayingOpen` 驱动的覆盖层（见下面 `NowPlayingScreen`），
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
                    },                    label = "route",
                ) { route ->
                    when (route) {
                        Route.Home -> HomeScreen(state, vm)
                        is Route.PlaylistSongs -> PlaylistScreen(route.playlist, state, vm)
                        is Route.ArtistDetail -> ArtistScreen(route.artistName, state, vm)
                        is Route.AlbumDetail -> AlbumScreen(route.albumName, route.albumId, state, vm)
                        Route.Login -> LoginScreen(state, vm)
                        Route.Config -> ConfigScreen(state, vm)
                        Route.ListenTogether -> ListenTogetherScreen(state, vm)
                        Route.NowPlaying, Route.Roam -> HomeScreen(state, vm)
                    }
                }

                if (state.nowPlayingOpen) {
                    NowPlayingScreen(state, vm)
                }

                // 播放列表（队列管理）：盖在详情页之上 —— 从详情页的控制行点队列图标唤起，
                // 详情页留在原地，收掉面板就回到详情页（网易云的层级关系）
                if (state.queueOpen) {
                    PlayQueuePanel(state, vm)
                }

                // 云盾验证页必须内嵌：它的验证结果是以 cookie 形式落在「那个浏览器」里的，
                // 甩给系统浏览器就等于把结果丢在门外（见 HypochloriteViewModel.completeLoginVerify）。
                if (state.showVerify && state.loginVerifyUrl.isNotEmpty()) {
                    LoginVerifyOverlay(state.loginVerifyUrl, vm)
                }

                if (state.roamTransitionDir != 0) {
                    RoamTransitionOverlay(
                        dir = state.roamTransitionDir,
                        onReadyToPlay = { vm.enterRoamNow() },
                        onFinished = { vm.finishRoamTransition() },
                    )
                }

                // 一起听的操作反馈。列表页点按/长按推歌都可能发生在任意页，房间页看不到 —— 所以挂根节点。
                ListenToast(state.listen.toast, vm::clearListenToast)
            }
        }
    }
}
