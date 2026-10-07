package com.abdurahmanharouat.syncedpass.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.ui.theme.Radius
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme

/*
 * The app's own components, drawn with Compose foundation only (no Material).
 * Shapes and colors follow the Proton Authenticator style guide.
 */

/** Tint for [Icon]; set by the containing component, like Material's LocalContentColor. */
val LocalContentColor = staticCompositionLocalOf { Color.Unspecified }

/** A Phosphor vector icon tinted with [LocalContentColor] (or [tint]). */
@Composable
fun Icon(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Image(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(if (tint == Color.Unspecified) SyncedPassTheme.colors.text else tint),
    )
}

@Composable
fun Text(text: String, style: TextStyle, color: Color = SyncedPassTheme.colors.text, modifier: Modifier = Modifier, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    BasicText(
        text = text,
        style = style.copy(color = color, textAlign = textAlign ?: TextAlign.Unspecified),
        modifier = modifier,
        maxLines = maxLines,
    )
}

/** Warm stone-gray page with the style guide's vertical gradient. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = SyncedPassTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.pageTop, c.pageBottom))),
        content = content,
    )
}

/** Full-width pill with the gold gradient and a soft glow. The one primary action per screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = SyncedPassTheme.colors
    val shape = CircleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.dropShadow(shape, Shadow(radius = 16.dp, color = c.primaryGlow, offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp))) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.primaryTop, c.primaryBottom)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(text, SyncedPassTheme.type.button, color = c.onPrimary)
    }
}

/** Same shape as [PrimaryButton], transparent with a thin border. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = SyncedPassTheme.colors.text) {
    val c = SyncedPassTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, c.textPlaceholder.copy(alpha = 0.6f)), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(text, SyncedPassTheme.type.button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = color)
    }
}

/** Round white button holding one icon, like Proton's settings button. */
@Composable
fun CircleIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SyncedPassTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(c.card)
            .border(1.dp, c.cardBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, contentDescription = null, tint = c.text)
    }
}

/** Plain icon button with a 48 dp touch target. */
@Composable
fun IconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, tint: Color = SyncedPassTheme.colors.text) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

/** Circular gold FAB with a "+", same gradient as the primary button. */
@Composable
fun FloatingAddButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SyncedPassTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .dropShadow(CircleShape, Shadow(radius = 16.dp, color = c.primaryGlow, offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp)))
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(c.primaryTop, c.primaryBottom)))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(R.drawable.ph_plus_regular, contentDescription = null, size = 26.dp, tint = c.onPrimary)
    }
}

/**
 * A labeled text field: the label sits above a filled, rounded box (the same
 * choice as the Mac app, where labels inside fields tested worse), so the
 * whole box is the tap target and typing starts at the left.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    required: Boolean = false,
    error: String? = null,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    singleLine: Boolean = true,
    /** Above 1, a text area: grows from this many lines, text starts top-left. */
    minLines: Int = 1,
    /** False for rows in a list under a section header (the label is still read by TalkBack). */
    showLabel: Boolean = true,
    /** Shown after the text (and the eye button), e.g. a remove button. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = SyncedPassTheme.colors
    val t = SyncedPassTheme.type
    var revealed by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (showLabel) {
            Row {
                Text(label, t.label, color = c.textWeak)
                if (required) Text(" *", t.label, color = c.error)
            }
            Spacer(Modifier.height(6.dp))
        }
        val area = minLines > 1
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine && !area,
            minLines = minLines,
            textStyle = (if (secret && revealed) t.mono else t.body1).copy(color = c.text),
            cursorBrush = SolidColor(c.primaryBottom),
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = !secret && keyboardType == KeyboardType.Text),
            keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    verticalAlignment = if (area) Alignment.Top else Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(Radius.mediumSmall))
                        .background(c.card)
                        .border(1.dp, if (error != null) c.error else c.cardBorder, RoundedCornerShape(Radius.mediumSmall))
                        // Same 52 dp height with or without the eye button.
                        .padding(
                            start = Spacing.l,
                            end = if (secret || trailing != null) Spacing.xs else Spacing.l,
                            top = if (area) Spacing.m else 6.dp,
                            bottom = if (area) Spacing.m else 6.dp,
                        ),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, t.body1, color = c.textPlaceholder, maxLines = 1)
                        inner()
                    }
                    if (secret) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { revealed = !revealed }
                                .semantics { contentDescription = if (revealed) "Hide $label" else "Show $label" },
                        ) {
                            Icon(if (revealed) R.drawable.ph_eye_slash_regular else R.drawable.ph_eye_regular, null, size = 22.dp, tint = c.textWeak)
                        }
                    }
                    trailing?.invoke()
                }
            },
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ph_warning_circle_regular, null, size = 16.dp, tint = c.error)
                Spacer(Modifier.width(Spacing.xs))
                Text(error, t.body2, color = c.error)
            }
        }
    }
}

/** White rounded card with a 1 dp border. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = SyncedPassTheme.colors
    val shape = RoundedCornerShape(Radius.medium)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

/** Centered empty state: gray illustration, headline, body, then actions. */
@Composable
fun EmptyState(@DrawableRes illustration: Int, headline: String, body: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val c = SyncedPassTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xxl),
    ) {
        Icon(illustration, contentDescription = null, size = 120.dp, tint = c.illustration)
        Spacer(Modifier.height(Spacing.xl))
        Text(headline, SyncedPassTheme.type.headline, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(body, SyncedPassTheme.type.body, color = c.textWeak, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xxl))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) { actions() }
    }
}

@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)
