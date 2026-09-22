package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.ThemeMode
import app.hypochlorite.netease.Quality
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.sections.AudioOutSection
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.argbHex
import app.hypochlorite.ui.theme.hex

@Composable
internal fun ConfigScreen(state: HomeState, vm: HypochloriteViewModel) {
    BackHandler { vm.back() }
    val colors = LocalHypochloriteColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickableNoRipple { vm.back() }
                    .padding(top = 8.dp, bottom = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrowIcon(color = colors.text, size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("设置")
        }
        Hairline()
        MonoText("音质", modifier = Modifier.padding(top = 16.dp))
        Quality.PRESETS.forEach { q ->
            val on = state.player.quality.id == q.id
            HoverBold(
                if (on) "> ${q.id}  ${q.label}" else "- ${q.id}  ${q.label}",
                onClick = { vm.setQuality(q.id) },
                modifier = Modifier.padding(top = 10.dp),
                on = on,
            )
        }
        AudioOutSection(state, vm)
        MonoText("主题", modifier = Modifier.padding(top = 28.dp))
        Hairline(Modifier.padding(top = 8.dp))
        MonoText("显示模式", muted = true, size = 14, modifier = Modifier.padding(top = 12.dp))
        ThemeMode.entries.forEach { mode ->
            val on = state.themeMode == mode
            HoverBold(
                if (on) "> ${mode.label}" else "- ${mode.label}",
                onClick = { vm.setThemeMode(mode) },
                modifier = Modifier.padding(top = 10.dp),
                on = on,
            )
        }
        HoverBold(
            if (state.monetEnabled) "> 封面取色  虚化封面背景并上色"
            else if (colors.isLight) "- 封面取色  当前为纯白"
            else "- 封面取色  当前为纯黑",
            onClick = { vm.setMonetEnabled(!state.monetEnabled) },
            modifier = Modifier.padding(top = 20.dp),
            on = state.monetEnabled,
        )
        HoverBold(
            if (state.revealEnabled) "> 切歌扩散动画  开" else "- 切歌扩散动画  关",
            onClick = { vm.setRevealEnabled(!state.revealEnabled) },
            modifier = Modifier.padding(top = 14.dp),
            on = state.revealEnabled,
        )
        MonoText("账号", modifier = Modifier.padding(top = 28.dp))
        Hairline(Modifier.padding(top = 8.dp))
        MonoText(
            if (state.loggedIn) "已登录" else "还没登录。",
            muted = true,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HoverBold("账号登录", onClick = { vm.openLogin() }, on = !state.loggedIn)
            if (state.loggedIn) {
                HoverBold("退出登录", onClick = { vm.logout() }, color = colors.muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
