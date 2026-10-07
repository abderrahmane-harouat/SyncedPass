package com.abdurahmanharouat.syncedpass.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.ui.theme.Radius
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme

/*
 * Form pieces for the login editor, following Proton Authenticator's form:
 * small uppercase section headers, white select rows with a ⇕ caret, and
 * choices in a bottom sheet over a scrim.
 */

/** Small uppercase header above a group of fields. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title.uppercase(), SyncedPassTheme.type.label.copy(letterSpacing = SyncedPassTheme.type.label.fontSize * 0.04f),
         color = SyncedPassTheme.colors.textWeak, modifier = modifier.padding(top = Spacing.s))
}

/** Gray helper text under a field or section. */
@Composable
fun FootnoteText(text: String, modifier: Modifier = Modifier) {
    Text(text, SyncedPassTheme.type.caption, color = SyncedPassTheme.colors.textWeak, modifier = modifier)
}

/**
 * A field that opens a list of choices: label above (like [LabeledField]),
 * the current value in a white box, and a ⇕ caret.
 */
@Composable
fun SelectField(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = SyncedPassTheme.colors
    val shape = RoundedCornerShape(Radius.mediumSmall)
    Column(modifier.fillMaxWidth()) {
        if (showLabel) {
            Text(label, SyncedPassTheme.type.label, color = c.textWeak)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.cardBorder, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = Spacing.l, end = Spacing.m, top = 6.dp, bottom = 6.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
            Icon(R.drawable.ph_caret_up_down_regular, null, size = 20.dp, tint = c.textWeak)
        }
    }
}

/** An accent text button with a leading icon, for "Add website" and similar. */
@Composable
fun AddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ph_plus_regular) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // Line the icon up with the fields' edge; the padding is only for the ripple.
            .offset(x = -Spacing.s)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.s),
    ) {
        Icon(icon, null, size = 20.dp, tint = c.accent)
        Spacer(Modifier.width(Spacing.s))
        Text(text, SyncedPassTheme.type.body1Medium, color = c.accent)
    }
}

/** A small round icon button for removing a row (websites, sign-in methods, custom fields). */
@Composable
fun RemoveButton(contentDescription: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(R.drawable.ph_minus_circle_regular, null, size = 22.dp, tint = SyncedPassTheme.colors.textWeak)
    }
}

/**
 * A sheet over a scrim at the bottom of the screen, with a centered title and
 * a round close button. Place it last inside the screen's root Box. Back and
 * a tap on the scrim close it.
 */
@Composable
fun BoxScope.BottomSheet(
    title: String,
    onDismiss: () -> Unit,
    /** Always 85% of the screen, e.g. for a list that shrinks while searching; otherwise it fits its content. */
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SyncedPassTheme.colors
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it / 3 } + fadeIn()) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (fullHeight) Modifier.fillMaxHeight(0.85f)
                    // As tall as its content, up to 85% of the screen.
                    else Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f),
                )
                .clip(RoundedCornerShape(topStart = Radius.large, topEnd = Radius.large))
                .background(c.pageBottom)
                // Swallow taps so they don't reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .imePadding()
                .padding(bottom = Spacing.l),
        ) {
            Box(Modifier.fillMaxWidth().padding(Spacing.l)) {
                Text(title, SyncedPassTheme.type.headline, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
                CircleIconButton(R.drawable.ph_x_regular, "Close", onDismiss, Modifier.align(Alignment.CenterEnd))
            }
            content()
        }
    }
}

/** One choice in a [BottomSheet]: leading icon, title, optional supporting text, a check when selected. */
@Composable
fun SheetRow(
    title: String,
    onClick: () -> Unit,
    supporting: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        if (leading != null) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(Spacing.m))
        }
        Column(Modifier.weight(1f)) {
            Text(title, SyncedPassTheme.type.body1, maxLines = 1)
            if (supporting != null) Text(supporting, SyncedPassTheme.type.body2, color = c.textWeak, maxLines = 1)
        }
        if (selected) Icon(R.drawable.ph_check_regular, "Selected", size = 20.dp, tint = c.accent)
    }
}

/** A rounded search box for filtering a list inside a sheet. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = SyncedPassTheme.colors
    val t = SyncedPassTheme.type
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = t.body1.copy(color = c.text),
        cursorBrush = SolidColor(c.primaryBottom),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder; contentDataType = ContentDataType.None },
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(c.card)
                    .border(1.dp, c.cardBorder, CircleShape)
                    .padding(horizontal = Spacing.l),
            ) {
                Icon(R.drawable.ph_magnifying_glass_regular, null, size = 20.dp, tint = c.textWeak)
                Spacer(Modifier.width(Spacing.m))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, t.body1, color = c.textPlaceholder, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** Centered gray message filling the rest of a sheet, for an empty list. */
@Composable
fun ColumnScope.SheetMessage(text: String) {
    Box(Modifier.fillMaxWidth().weight(1f).padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(text, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center)
    }
}
