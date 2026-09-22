package app.hypochlorite.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hypochlorite.HomeState
import app.hypochlorite.HypochloriteViewModel
import app.hypochlorite.ui.BackArrowIcon
import app.hypochlorite.ui.Hairline
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.clickableNoRipple
import app.hypochlorite.ui.theme.BodyStyle
import app.hypochlorite.ui.theme.LocalHypochloriteColors
import app.hypochlorite.ui.theme.Warn

@Composable
internal fun LoginScreen(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current
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
            MonoText("账号登录")
        }
        Hairline()

        // ---- 登录方式切换 ----
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HoverBold(
                if (state.loginTab == 0) "> 扫码登录" else "- 扫码登录",
                onClick = { vm.setLoginTab(0) },
                on = state.loginTab == 0,
                padV = 9,
            )
            HoverBold(
                if (state.loginTab == 1) "> 手机号登录" else "- 手机号登录",
                onClick = { vm.setLoginTab(1) },
                on = state.loginTab == 1,
                padV = 9,
            )
        }
        Hairline(Modifier.padding(top = 10.dp))

        if (state.loginTab == 0) {
            QrLoginPane(state, vm)
        } else {
            PhoneLoginPane(state, vm)
        }

        MonoText("高级登录方式：cookie / MUSIC_U (可选)", modifier = Modifier.padding(top = 26.dp), muted = true)
        BasicTextField(
            value = state.cookieInput,
            onValueChange = vm::setCookieInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(72.dp),
            textStyle = BodyStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            decorationBox = { inner ->
                Column {
                    Box(Modifier.weight(1f)) { inner() }
                    Hairline()
                }
            },
        )
        Row(Modifier.padding(top = 12.dp)) {
            HoverBold("提交 Cookie 登录", onClick = { vm.submitCookie() })
        }
            if (state.loginMsg.isNotEmpty()) {
                MonoText(
                    state.loginMsg,
                    color = if (isLoginError(state.loginMsg)) Warn else colors.text,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 云盾挡下来时给个出口：光重试没用，必须在同一个会话里过一次验证
            if (state.loginVerifyUrl.isNotEmpty()) {
                HoverBold(
                    "过一次安全验证",
                    onClick = { vm.openLoginVerify() },
                    modifier = Modifier.padding(top = 12.dp),
                    on = true,
                    padV = 9,
                )
                MonoText(
                    "在这里过一次验证就行，过了会自动回来重试登录。",
                    muted = true,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
    }
}

private fun isLoginError(msg: String): Boolean =
    listOf("失败", "无效", "过期", "不对", "不能为空", "错误", "风控", "拦").any { msg.contains(it) }

@Composable
private fun QrLoginPane(state: HomeState, vm: HypochloriteViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(188.dp)
            .padding(top = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.qr != null) {
            Image(state.qr, contentDescription = "qr", modifier = Modifier.size(168.dp))
        } else {
            MonoText("扫码登录", muted = true)
        }
    }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HoverBold("刷新二维码", onClick = { vm.requestQr() }, on = true)
        HoverBold("退出登录", onClick = { vm.logout() }, color = LocalHypochloriteColors.current.muted)
    }
    // 二维码不是「坏」了，是它第一次轮询就被云盾挡下来（还没人扫就先 -462）。
    // 这里得说清楚，否则用户只会以为二维码一直转是卡住了。
    if (state.loginVerifyUrl.isNotEmpty()) {
        MonoText(
            "这条路也在走云盾：码还没人扫，第一次查询就被挡了。" +
                "先点上面的「过一次安全验证」，或者直接用下面的 cookie 登录。",
            color = Warn,
            size = 13,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun PhoneLoginPane(state: HomeState, vm: HypochloriteViewModel) {
    val colors = LocalHypochloriteColors.current

    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        HoverBold(
            if (state.phoneUseCaptcha) "> 验证码登录" else "- 验证码登录",
            onClick = { vm.setPhoneUseCaptcha(true) },
            on = state.phoneUseCaptcha,
            padV = 9,
        )
        HoverBold(
            if (!state.phoneUseCaptcha) "> 密码登录" else "- 密码登录",
            onClick = { vm.setPhoneUseCaptcha(false) },
            on = !state.phoneUseCaptcha,
            padV = 9,
        )
    }

    // 国家区号 + 手机号
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoginField(
            label = "区号",
            value = "+${state.phoneCountry}",
            onValueChange = vm::setPhoneCountry,
            modifier = Modifier.width(72.dp),
            keyboardType = KeyboardType.Number,
        )
        LoginField(
            label = "手机号",
            value = state.phone,
            onValueChange = vm::setPhone,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Phone,
            placeholder = "11 位手机号",
        )
    }

    if (state.phoneUseCaptcha) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LoginField(
                label = "短信验证码",
                value = state.phoneCaptcha,
                onValueChange = vm::setPhoneCaptcha,
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.NumberPassword,
                placeholder = "6 位数字",
            )
            HoverBold(
                text = when {
                    state.phoneSending -> "发送中…"
                    state.phoneCountdown > 0 -> "${state.phoneCountdown}s"
                    state.loginCooldown > 0 -> "${state.loginCooldown}s"
                    else -> "获取验证码"
                },
                onClick = { vm.sendSmsCode() },
                color = if (state.phoneCountdown > 0 || state.phoneSending || state.loginCooldown > 0) {
                    colors.muted
                } else {
                    colors.text
                },
                modifier = Modifier.padding(bottom = 4.dp),
                padV = 9,
            )
        }
    } else {
        LoginField(
            label = "密码",
            value = state.phonePassword,
            onValueChange = vm::setPhonePassword,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = "网易云账号密码",
        )
    }

    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        HoverBold(
            when {
                state.phoneLoggingIn -> "登录中…"
                state.loginCooldown > 0 -> "${state.loginCooldown}s"
                else -> "登录"
            },
            onClick = { vm.submitPhoneLogin() },
            color = if (state.phoneLoggingIn || state.loginCooldown > 0) colors.muted else colors.text,
            on = !state.phoneLoggingIn && state.loginCooldown == 0,
            padV = 9,
        )
        HoverBold("退出登录", onClick = { vm.logout() }, color = colors.muted, padV = 9)
    }

    if (state.loginCooldown > 0) {
        MonoText(
            if (state.loginVerifyUrl.isNotEmpty()) {
                "网易这次要的是人机验证 —— 点下面的「过一次安全验证」，过了会自动重试登录。"
            } else {
                "被拦下来了，这会儿别急着再点。连续失败会让风控记更久。"
            },
            modifier = Modifier.padding(top = 14.dp),
            muted = true,
        )
    }
}

/** 下划线式输入框，和项目里 cookie 输入框同一套视觉语言。 */
@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = LocalHypochloriteColors.current
    Column(modifier) {
        MonoText(label, muted = true, size = 14)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(34.dp),
            textStyle = BodyStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Column {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            MonoText(placeholder, muted = true)
                        }
                        inner()
                    }
                    Hairline()
                }
            },
        )
    }
}
