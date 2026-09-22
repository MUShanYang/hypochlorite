package app.hypochlorite.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.netease.Crypto
import app.hypochlorite.netease.ListenRoomKind
import app.hypochlorite.netease.Song
import app.hypochlorite.player.ListenLink
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
    val context = LocalContext.current
    val lt = state.listen
    val room = lt.room
    val kind = remember { mutableStateOf(ListenRoomKind.Duo) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isEmpty()) return@rememberLauncherForActivityResult
        vm.setListenInput(raw)
        vm.listenJoinRoom(raw)
    }
    var seated by remember { mutableStateOf(room != null) }
    LaunchedEffect(room?.roomId) {
        if (room != null) seated = true
        else if (seated) vm.dismissListenScreen()
    }
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

        if (room == null && seated) {
            MonoText("一起听已经结束了", modifier = Modifier.padding(top = 20.dp))
        } else if (room == null) {
            if (!state.loggedIn) {
                HoverBold("账号登录", onClick = { vm.openLogin() }, modifier = Modifier.padding(top = 10.dp))
            } else {
                MonoText("创建房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HoverBold(
                        if (kind.value == ListenRoomKind.Duo) "> 双人" else "- 双人",
                        onClick = { kind.value = ListenRoomKind.Duo },
                        on = kind.value == ListenRoomKind.Duo,
                        padV = 9,
                    )
                    HoverBold(
                        if (kind.value == ListenRoomKind.Multi) "> 多人" else "- 多人",
                        onClick = { kind.value = ListenRoomKind.Multi },
                        on = kind.value == ListenRoomKind.Multi,
                        padV = 9,
                    )
                }
                HoverBold(
                    "> 创建房间",
                    onClick = { vm.listenCreateRoom(kind.value) },
                    modifier = Modifier.padding(top = 4.dp),
                    on = !lt.syncing,
                )

                MonoText("加入房间", modifier = Modifier.padding(top = 28.dp))
                Hairline(Modifier.padding(top = 8.dp))
                UnderlineField(
                    value = state.listenInput,
                    onValueChange = { vm.setListenInput(it) },
                    placeholder = "邀请链接",
                    imeAction = ImeAction.Done,
                    onGo = { vm.listenJoinRoom() },
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HoverBold(
                        "> 扫码加入",
                        onClick = {
                            val options = ScanOptions().apply {
                                setBeepEnabled(false)
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("扫描邀请二维码")
                            }
                            runCatching { scanLauncher.launch(options) }.onFailure {
                                vm.listenNote("这台机器开不了相机，改贴链接吧")
                            }
                        },
                        on = !lt.syncing,
                    )
                    HoverBold(
                        "> 加入",
                        onClick = { vm.listenJoinRoom() },
                        on = state.listenInput.isNotEmpty() && !lt.syncing,
                    )
                }
            }
            lt.error?.takeIf { it.isNotEmpty() }?.let { message ->
                MonoText(message, color = Warn, size = 13, modifier = Modifier.padding(top = 18.dp))
            }
        } else {
            val shareUrl = Crypto.ltShareUrl(room.roomId, state.player.current?.id, room.inviterId ?: state.profileUserId)
            var qr by remember(shareUrl) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(shareUrl) {
                qr = vm.inviteQr(shareUrl)
            }
            Column(Modifier.padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(if (lt.hosting) "[房主]" else "[成员]", bold = true, size = 16)
                    Spacer(Modifier.width(8.dp))
                    MonoText(linkLabel(lt.link, lt.syncing), muted = true, size = 13)
                }
                MonoText("房间", modifier = Modifier.padding(top = 14.dp), muted = true, size = 12)
                MonoText(
                    room.roomId,
                    modifier = Modifier.padding(top = 4.dp),
                    bold = true,
                    size = 14,
                    maxLines = 2,
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HoverBold(
                        "复制邀请链接",
                        onClick = { vm.copyText(shareUrl, "已复制") },
                        padV = 9,
                    )
                    HoverBold(
                        "分享邀请",
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareUrl)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(send, "分享邀请"))
                            }.onFailure {
                                vm.listenNotify("分享没能打开")
                            }
                        },
                        padV = 9,
                    )
                }
                val inviteQr = qr
                if (inviteQr != null) {
                    Image(
                        bitmap = inviteQr,
                        contentDescription = "一起听邀请",
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(168.dp),
                    )
                }
            }

            MonoText("一起听的人 ${room.memberCount}", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            if (room.users.isNotEmpty()) {
                room.users.forEach { u ->
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
                        if (u.userId == room.ownerId) MonoText("房主", muted = true, size = 12)
                    }
                }
            }

            RoomQueue(
                queue = lt.roomQueue,
                currentId = state.player.current?.id,
                vm = vm,
            )
            

            lt.error?.takeIf { it.isNotEmpty() }?.let { message ->
                MonoText(message, color = Warn, size = 13, modifier = Modifier.padding(top = 20.dp))
            }

            MonoText("房间", modifier = Modifier.padding(top = 28.dp))
            Hairline(Modifier.padding(top = 8.dp))
            HoverBold(
                if (lt.hosting) "结束一起听" else "离开房间",
                onClick = { vm.listenLeaveRoom() },
                color = Warn,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoomQueue(
    queue: List<Song>,
    currentId: String?,
    vm: HypochloriteViewModel,
) {
    val colors = LocalHypochloriteColors.current
    MonoText("房间队列", modifier = Modifier.padding(top = 28.dp))
    Hairline(Modifier.padding(top = 8.dp))
    MonoText(
        "${queue.size} 首",
        muted = true,
        size = 13,
        modifier = Modifier.padding(top = 10.dp),
    )
    queue.take(30).forEachIndexed { i, song ->
        val nowPlaying = currentId == song.id
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple { vm.playSong(song) }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText((i + 1).toString().padStart(2, '0'), muted = !nowPlaying, size = 12)
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
    if (queue.size > 30) {
        MonoText(
            "…还有 ${queue.size - 30} 首",
            muted = true,
            size = 12,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun linkLabel(link: ListenLink, syncing: Boolean): String = when {
    syncing -> "同步中…"
    link == ListenLink.Reconnecting -> "连接断了，正在恢复…"
    link == ListenLink.Connecting -> "正在接通…"
    link == ListenLink.Live -> "已同步"
    else -> "已同步"
}
