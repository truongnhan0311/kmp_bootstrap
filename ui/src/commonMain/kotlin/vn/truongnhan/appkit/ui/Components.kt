package vn.truongnhan.appkit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Bộ component nhỏ, đặt tên và API theo shadcn (Card, Button, Input, Badge, Alert, Tabs…) trên nền Material 3.
 * Mọi màu lấy từ `A.colors` (token shadcn) nên đổi theme là đổi tất cả. Thêm component mới: viết một @Composable ở đây,
 * chỉ đọc `A.colors`/`A.type`, không giữ màu cứng.
 */

/** Chữ với kiểu + màu của theme. Màu mặc định là `foreground`. */
@Composable
fun Txt(text: String, style: TextStyle = A.type.body, color: Color = A.colors.foreground, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = maxLines)
}

@Composable
fun Muted(text: String, style: TextStyle = A.type.small, modifier: Modifier = Modifier) = Txt(text, style, A.colors.mutedForeground, modifier)

/** shadcn Card: nền `card`, viền `border`, bo 14dp. `outline` đổi màu viền (ví dụ viền theo mức tải). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    outline: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier.fillMaxWidth().clip(shape).background(A.colors.card)
            .border(BorderStroke(1.dp, outline ?: A.colors.border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Biến thể của nút, như `variant` của shadcn Button. */
enum class ButtonVariant { Default, Outline, Destructive, Ghost }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    enabled: Boolean = true,
    busy: Boolean = false,
    small: Boolean = false,
) {
    val c = A.colors
    val shape = RoundedCornerShape(10.dp)
    val bg = if (variant == ButtonVariant.Default) c.primary else Color.Transparent
    val fg = when (variant) { ButtonVariant.Default -> c.primaryForeground; ButtonVariant.Destructive -> c.destructive; else -> c.foreground }
    val line = when (variant) { ButtonVariant.Default -> c.primary; ButtonVariant.Destructive -> c.destructive; ButtonVariant.Outline -> c.border; ButtonVariant.Ghost -> Color.Transparent }
    val alpha = if (enabled && !busy) 1f else 0.5f
    Row(
        modifier.clip(shape).background(bg.copy(alpha = bg.alpha * alpha)).border(BorderStroke(1.dp, line.copy(alpha = line.alpha * alpha)), shape)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 7.dp else 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
            Box(Modifier.size(8.dp))
        }
        Text(text, style = A.type.body.copy(fontSize = if (small) 13.sp else 14.sp), color = fg.copy(alpha = alpha))
    }
}

/** shadcn Input kèm nhãn phía trên. Số liệu dùng `numeric = true` (số = chữ mono). */
@Composable
fun AppField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    badge: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
) {
    val c = A.colors
    val style = if (numeric) A.type.numeric else A.type.body
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, A.type.small.copy(fontSize = 13.sp))
            if (badge != null) {
                Box(Modifier.size(8.dp))
                Txt(badge, A.type.tiny.copy(fontSize = 10.sp), c.mutedForeground, Modifier.clip(RoundedCornerShape(4.dp)).background(c.muted).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = singleLine,
            textStyle = style.copy(color = c.foreground),
            placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, color = c.mutedForeground, style = style) }),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.ring, unfocusedBorderColor = c.input, focusedTextColor = c.foreground, unfocusedTextColor = c.foreground,
                cursorColor = c.ring, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            ),
        )
        if (hint != null) Muted(hint)
    }
}

/** shadcn Alert. `destructive = true` cho lỗi, ngược lại là thông báo màu chính. */
@Composable
fun AppAlert(message: String, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val tone = if (destructive) A.colors.destructive else A.colors.primary
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(tone.copy(alpha = 0.12f)).border(BorderStroke(1.dp, tone.copy(alpha = 0.6f)), shape).padding(12.dp)) {
        Txt(message, A.type.body, tone)
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, label: String? = null) {
    Row(modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = A.colors.mutedForeground)
        if (label != null) { Box(Modifier.size(8.dp)); Muted(label, A.type.body) }
    }
}

/** shadcn Badge: nhãn nhỏ bo tròn. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = A.colors.mutedForeground) {
    Txt(text, A.type.tiny, color, modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, A.colors.border), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** Chọn một trong vài lựa chọn (shadcn Tabs / ToggleGroup). */
@Composable
fun <V> Segmented(options: List<Pair<V, String>>, selected: V, onSelect: (V) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = A.colors
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(c.muted).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Txt(
                label, A.type.small.copy(fontSize = 13.sp), if (active) c.foreground else c.mutedForeground,
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) c.background else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { if (!active) onSelect(value) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Nhãn + giá trị số (chữ mono) trong lưới chỉ số. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false, color: Color = A.colors.foreground) {
    Column(modifier) {
        Muted(label)
        Txt(value, if (big) A.type.numericLarge else A.type.numeric.copy(fontSize = 18.sp), if (big) A.colors.primary else color)
    }
}

/** Ảnh đại diện: có ảnh thì hiện, không thì chữ cái đầu. Việc tải ảnh để app lo (kit không biết API nào). */
@Composable
fun Avatar(name: String?, image: ImageBitmap? = null, size: Int = 32, modifier: Modifier = Modifier) {
    val c = A.colors
    Box(modifier.size(size.dp).clip(CircleShape).background(c.muted).border(BorderStroke(1.dp, c.border), CircleShape), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
        else Txt(name?.trim()?.firstOrNull()?.uppercase() ?: "?", A.type.body.copy(fontSize = (size * 0.42f).sp), c.mutedForeground)
    }
}

/** Tiêu đề màn hình + phụ đề (chữ serif: câu do người viết, không phải số). */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Txt(title, A.type.title)
        if (subtitle != null) Txt(subtitle, A.type.prose, A.colors.mutedForeground, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Txt(text, A.type.section)
        if (trailing != null) { Box(Modifier.size(8.dp)); Muted(trailing, A.type.body) }
    }
}

@Composable
fun Gap(h: Int = 12) = Box(Modifier.height(h.dp))

/** Trạng thái tải dữ liệu: đang tải / lỗi (có nút thử lại) / có dữ liệu. App dùng cho bất kỳ màn nào gọi API. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

@Composable
fun <T> LoadableView(state: Loadable<T>, onRetry: (() -> Unit)? = null, loadingLabel: String = "…", retryLabel: String = "Retry", content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Spinner(label = loadingLabel)
        is Loadable.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppAlert(state.message, destructive = true)
            if (onRetry != null) AppButton(retryLabel, onRetry, variant = ButtonVariant.Outline, small = true)
        }
        is Loadable.Ready -> content(state.value)
    }
}
