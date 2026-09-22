package app.hypochlorite.ui.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hypochlorite.ui.HoverBold
import app.hypochlorite.ui.MonoText
import app.hypochlorite.ui.theme.LocalHypochloriteColors

@Composable
internal fun SwitchRow(
    label: String,
    sub: String = "",
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val liveColors = LocalHypochloriteColors.current
    val tone = if (!enabled) liveColors.muted.copy(alpha = 0.55f) else liveColors.text
    Column(modifier) {
        HoverBold(
            if (on) "> $label" else "- $label",
            onClick = { if (enabled) onClick() },
            modifier = Modifier.padding(top = 2.dp),
            color = tone,
            on = on,
            padV = 8,
        )
        if (sub.isNotEmpty()) {
            MonoText(sub, muted = true, modifier = Modifier.padding(top = 2.dp), size = 14)
        }
    }
}
