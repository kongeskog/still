package com.kongeskog.still.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD

/*
 * One design language for every screen, taken from Mudita's Messages app: a heavy rule under
 * a bold title, open line icons, bold names over dotted row dividers, and no explanatory prose.
 */

private fun lineIcon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw,
        )
        .build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

object Icons {
    val Back = lineIcon("Back") { moveTo(20f, 12f); lineTo(4f, 12f); moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f) }
    val More = lineIcon("More") { listOf(5f, 12f, 19f).forEach { circle(it, 12f, 2f) } }
    val Send = lineIcon("Send") { moveTo(3f, 4f); lineTo(21f, 12f); lineTo(3f, 20f); lineTo(6f, 12f); close(); moveTo(6f, 12f); lineTo(13f, 12f) }
    val Plus = lineIcon("New") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Chevron = lineIcon("Open") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val HideKeyboard = lineIcon("Hide keyboard") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Close = lineIcon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = lineIcon("Selected") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
    val Settings = lineIcon("Settings") {
        listOf(6f to 15f, 12f to 7f, 18f to 13f).forEach { (y, knob) ->
            moveTo(3f, y); lineTo(knob - 2f, y); moveTo(knob + 2f, y); lineTo(21f, y)
            circle(knob, y, 2f)
        }
    }
    val Phone = lineIcon("Call") {
        moveTo(6f, 3f); lineTo(9f, 3f); lineTo(10.5f, 7.5f); lineTo(8.5f, 9f)
        arcToRelative(11f, 11f, 0f, false, false, 6.5f, 6.5f)
        lineTo(16.5f, 13.5f); lineTo(21f, 15f); lineTo(21f, 18f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        arcToRelative(15f, 15f, 0f, false, true, -15f, -15f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
    }
    val MicOff = lineIcon("Mute") {
        moveTo(9f, 6f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); lineTo(15f, 11f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f); close()
        moveTo(6f, 11f); arcToRelative(6f, 6f, 0f, false, false, 12f, 0f); moveTo(12f, 17f); lineTo(12f, 21f)
        moveTo(4f, 3f); lineTo(20f, 21f)
    }
    val Speaker = lineIcon("Speaker") {
        moveTo(3f, 9.5f); lineTo(7f, 9.5f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 14.5f); lineTo(3f, 14.5f); close()
        moveTo(15.5f, 9f); arcToRelative(4f, 4f, 0f, false, true, 0f, 6f)
        moveTo(18f, 6.5f); arcToRelative(7.5f, 7.5f, 0f, false, true, 0f, 11f)
    }
    val Search = lineIcon("Search") { circle(10.5f, 10.5f, 6f); moveTo(15f, 15f); lineTo(20.5f, 20.5f) }
    val Attach = lineIcon("Attach") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(20f, 16f)
        circle(15.5f, 8.5f, 1.2f)
    }
    val Mic = lineIcon("Voice note") {
        moveTo(9f, 6f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); lineTo(15f, 11f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f); close()
        moveTo(6f, 11f); arcToRelative(6f, 6f, 0f, false, false, 12f, 0f); moveTo(12f, 17f); lineTo(12f, 21f)
    }
    val Play = lineIcon("Play") { moveTo(7f, 5f); lineTo(19f, 12f); lineTo(7f, 19f); close() }
    val Stop = lineIcon("Stop") { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    val File = lineIcon("File") { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f) }
    val Reply = lineIcon("Reply") { moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f); moveTo(4f, 12f); lineTo(14f, 12f); arcToRelative(6f, 6f, 0f, false, true, 6f, 6f) }
}

/** A white full-screen column under the Messages-style bar. */
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    /** The small line above the title, as "SMS" sits above a name in Messages. */
    label: String? = null,
    onTitle: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                BarAction(Icons.Back, "Back", onBack)
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier)) {
                if (label != null) TextMMD(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextMMD(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        HorizontalDividerMMD()
        content()
    }
}

/** A 48dp icon button for the app bar. */
@Composable
fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(30.dp)) }
}

/** The filled "+" of the Messages list: the one primary action on a screen. */
@Composable
fun BarPrimary(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(42.dp).background(Color.Black, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(26.dp), tint = Color.White)
        }
    }
}

/** A 40dp outlined box like the Messages send button, inside a full 48dp tap target. */
@Composable
fun SquareButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).border(2.dp, Color.Black, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp))
        }
    }
}

/** The dotted rule between list rows in Messages, inset from both edges. */
@Composable
fun DottedDivider() {
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)) {
        drawLine(
            Color.Black, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)),
        )
    }
}

/** A list row of fixed height: a bold line, an optional second line, and something at the end. */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    bold: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(
                title, style = MaterialTheme.typography.titleMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) TextMMD(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
    DottedDivider()
}

/** A row that opens another screen. */
@Composable
fun NavRow(title: String, detail: String? = null, onClick: () -> Unit) =
    ListRow(title, detail, onClick = onClick) { Icon(Icons.Chevron, null, Modifier.size(24.dp)) }

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListRow(title, onClick = { onChange(!checked) }) { SwitchMMD(checked = checked, onCheckedChange = onChange) }

/** One of several exclusive choices; the chosen one carries a tick. */
@Composable
fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) =
    ListRow(title, bold = selected, onClick = onClick) { if (selected) Icon(Icons.Check, "Selected", Modifier.size(26.dp)) }

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    ButtonMMD(onClick, modifier.height(56.dp), enabled) {
        TextMMD(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButtonMMD(onClick, modifier.height(56.dp), enabled) {
        TextMMD(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
