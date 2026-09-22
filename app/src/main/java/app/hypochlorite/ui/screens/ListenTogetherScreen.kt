package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.Crypto
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.UnderlineField
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.Warn

@Composable
internal fun ListenTogetherScreen(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
    val lt = state.listen
    val room = lt.room
    BackHandler { vm.back() }
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
                BackArrowIcon(size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            MonoText("一起听")
            Spacer(Modifier.weight(1f))
            if (lt.syncing) MonoText("同步中…", muted = true, size = 12)
        }
        Hairline()

        if (room == null) {
            // ---------------------------------------------------- 未进房
            MonoText("和另一个人听同一首歌", modifier = Modifier.padding(top = 20.dp), bold = true, size = 20)
            if (!state.loggedIn) {
                MonoText(
                    "一起听要挂在你的网易云账号上，先去登录。",
                    color = Warn,
                    size = 13,
                    modifier = Modifier.padding(top = 16.dp),
                )
                HoverBold("账号登录", onClick = { vm.openLogin() }, modifier = Modifier.padding(top = 10.dp))
            } else {
                MonoText("创建房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                HoverBold(
                    "> 创建房间  成为房主",
                    onClick = { vm.listenCreateRoom() },
                    modifier = Modifier.padding(top = 10.dp),
                    on = true,
                )

                MonoText("加入房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                UnderlineField(
                    value = state.listenInput,
                    onValueChange = { vm.setListenInput(it) },
                    placeholder = "输入房间号",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    onGo = { vm.listenJoinRoom() },
                    modifier = Modifier.padding(top = 12.dp),
                )
                HoverBold(
                    "> 加入",
                    onClick = { vm.listenJoinRoom() },
                    modifier = Modifier.padding(top = 6.dp),
                    on = state.listenInput.isNotEmpty(),
                )
            }
        } else {
            // ---------------------------------------------------- 在房间里
            Column(Modifier.padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(if (lt.hosting) "[房主]" else "[成员]", bold = true, size = 16)
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        when {
                            !lt.connected -> "掉线了，正在重连…"
                            lt.syncing -> "同步中…"
                            else -> "已同步"
                        },
                        muted = true,
                        size = 13,
                    )
                }
                MonoText(
                    "房间号 ${room.roomId}",
                    modifier = Modifier.padding(top = 10.dp),
                    bold = true,
                    size = 22,
                )
                // 房间号是纯数字，分享链接是给另一台设备直接点开的。两个都给，
                // 因为「发给朋友」和「自己换设备接着听」是两种不同的用法。
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HoverBold(
                        "复制房间号",
                        onClick = { vm.copyText(room.roomId, "房间号复制好了") },
                        padV = 9,
                    )
                    HoverBold(
                        "复制链接",
                        onClick = {
                            vm.copyText(
                                Crypto.ltShareUrl(room.roomId, state.player.current?.id, state.profileUserId),
                                "链接复制好了，用网易云打开就能进",
                            )
                        },
                        padV = 9,
                    )
                }
            }

            MonoText("一起听的人 ${room.memberCount}", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            if (room.users.isEmpty()) {
                MonoText(
                    "房间里只有你。把房间号发出去，等人进来。",
                    muted = true,
                    size = 13,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                room.users.forEachIndexed { i, u ->
                    val me = u.userId == state.profileUserId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            if (me) "> ${u.nickname.ifEmpty { "我" }}" else "- ${u.nickname.ifEmpty { "用户 ${u.userId.takeLast(6)}" }}",
                            bold = me || u.userId == room.ownerId,
                            maxLines = 1,
                            marquee = true,
                        )
                        Spacer(Modifier.weight(1f))
                        if (u.userId == room.ownerId) {
                            MonoText("房主", muted = true, size = 12)
                        }
                    }
                }
            }

            MonoText("房间队列", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            MonoText(
                "${lt.roomQueue.size} 首",
                muted = true,
                size = 13,
                modifier = Modifier.padding(top = 10.dp),
            )
            // 房间里队列是共享的 —— 没有「推/拉」的概念了，直接把它显示出来。
            // 点一行就是切歌（走房间指令），长按是加歌，两条路都在列表页可用。
            lt.roomQueue.take(30).forEachIndexed { i, song ->
                val nowPlaying = state.player.current?.id == song.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { vm.playSong(song) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        (i + 1).toString().padStart(2, '0'),
                        muted = !nowPlaying,
                        size = 12,
                    )
                    Spacer(Modifier.width(12.dp))
                    MonoText(
                        song.name,
                        bold = nowPlaying,
                        color = if (nowPlaying) colors.accent else colors.text,
                        maxLines = 1,
                        marquee = true,
                        size = 14,
                        modifier = Modifier.weight(1f),
                    )
                    if (nowPlaying) {
                        Spacer(Modifier.width(8.dp))
                        MonoText("[播放中]", color = colors.accent, size = 11, bold = true)
                    }
                }
            }
            if (lt.roomQueue.size > 30) {
                MonoText(
                    "…还有 ${lt.roomQueue.size - 30} 首",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (lt.roomQueue.isEmpty()) {
                MonoText(
                    "还没歌。在任意列表里点一首就能放进来，或者长按某首歌只加不放。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!lt.error.isNullOrEmpty()) {
                MonoText(lt.error!!, color = Warn, size = 13, modifier = Modifier.padding(top = 20.dp))
            }

            MonoText("房间", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            HoverBold(
                "退出房间",
                onClick = { vm.listenLeaveRoom() },
                color = Warn,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (lt.hosting) {
                MonoText(
                    "你是房主，你退出房间就散了，其他人会被一起断开。",
                    muted = true,
                    size = 12,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
