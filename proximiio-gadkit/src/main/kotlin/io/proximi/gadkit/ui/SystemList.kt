package io.proximi.gadkit.ui

import io.proximi.gadkit.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.glassShadow

// The iOS 26 searchable inset grouped list (iOS `List` with `.searchable` in a `NavigationStack`),
// as the place search and the visit plan picker draw it on iOS.

/** iOS `systemGroupedBackground`. */
internal val GroupedBackground = Color(0xFFF2F2F7)

/** iOS `secondaryLabel`. */
internal val SecondaryLabel = Color(0x993C3C43)

/** The list separator. */
internal val ListSeparator = Color(0xFFE8E8E8)

/** Corner radius of a list section. */
internal val ListSectionRadius = 26.dp

/** Navigation title: the system font, 17 sp semibold. */
internal val SystemTitle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)

/** Search field text and toolbar buttons: the system font, 17 sp. */
internal val SystemBody = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp)

/** The shape of row [index] of [count] in a section: the first and last rows carry the section corners. */
internal fun listRowShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) ListSectionRadius else 0.dp
    val bottom = if (index == count - 1) ListSectionRadius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The search bar: magnifier and [placeholder] in a white capsule, 44 dp high, with a soft shadow. */
@Composable
internal fun SystemSearchField(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = SystemBody.copy(color = Palette.ink),
        cursorBrush = SolidColor(Palette.ink),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .glassShadow(CircleShape, Color.Black.copy(alpha = 0.08f), offsetY = 4.dp, blur = 24.dp)
                    .background(Palette.white, CircleShape)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.icon_search), null, Modifier.size(20.dp), tint = Palette.ink)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, style = SystemBody, color = SecondaryLabel)
                    field()
                }
            }
        },
    )
}
