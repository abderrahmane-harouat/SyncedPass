package com.abdurahmanharouat.syncedpass.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.model.CustomField
import com.abdurahmanharouat.syncedpass.model.KnownService
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.accountLabel
import com.abdurahmanharouat.syncedpass.ui.components.Card
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.IconButton
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.SectionHeader
import com.abdurahmanharouat.syncedpass.ui.components.ServiceIcon
import com.abdurahmanharouat.syncedpass.ui.components.SignInMethodIcon
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * Read-only view of a login, like the Mac's LoginDetailView: only what the
 * login has, with secrets masked until revealed and a copy button on every
 * value. Edit opens the editor for this login.
 */
@Composable
fun LoginDetailScreen(
    item: LoginItem,
    allItems: List<LoginItem>,
    onEdit: () -> Unit,
    onOpen: (UUID) -> Unit,
    onBack: () -> Unit,
) {
    val c = SyncedPassTheme.colors
    val service = KnownService.matching(item)
    val usedBy = allItems.filter { it.signsInWith(item.id) }.sortedBy { it.title.lowercase() }
    BackHandler(onBack = onBack)

    // Keyed by login, so revealed secrets are hidden again when another login opens.
    key(item.id) { Page {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Fixed top bar, outside the scrolling content.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.s),
            ) {
                IconButton(R.drawable.ph_arrow_left_regular, "Back", onBack)
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onEdit)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                ) {
                    Icon(R.drawable.ph_pencil_simple_regular, null, size = 20.dp, tint = c.accent)
                    Spacer(Modifier.width(Spacing.s))
                    Text("Edit", SyncedPassTheme.type.button, color = c.accent)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xxl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceIcon(item, size = 56.dp, ringColor = c.pageTop)
                    Spacer(Modifier.width(Spacing.l))
                    Column {
                        SelectionContainer { Text(item.title, SyncedPassTheme.type.subtitle) }
                        if (service != null && !service.name.equals(item.title, ignoreCase = true)) {
                            Text(service.name, SyncedPassTheme.type.body1, color = c.textWeak)
                        }
                    }
                }

                if (listOf(item.email, item.username, item.password, item.totpSecret).any { it.isNotEmpty() }) {
                    Group {
                        ValueRow("Email", item.email)
                        ValueRow("Username", item.username)
                        ValueRow("Password", item.password, secret = true)
                        ValueRow("2FA secret (TOTP)", item.totpSecret, secret = true)
                    }
                }

                if (item.websites.isNotEmpty()) {
                    Section("Websites") {
                        Group { item.websites.forEach { WebsiteRow(it) } }
                    }
                }

                if (item.signIns.isNotEmpty()) {
                    Section("Sign-in methods") {
                        Group {
                            item.signIns.forEach { signIn ->
                                val account = signIn.accountID?.let { id -> allItems.firstOrNull { it.id == id } }
                                LinkRow(
                                    leading = { SignInMethodIcon(signIn.method, 28.dp) },
                                    title = signIn.method.displayName,
                                    supporting = when {
                                        account != null -> account.accountLabel
                                        signIn.accountID != null -> "Deleted login"
                                        else -> null
                                    },
                                    onClick = account?.let { { onOpen(it.id) } },
                                )
                            }
                        }
                    }
                }

                if (usedBy.isNotEmpty()) {
                    Section("Used to sign in to (${usedBy.size})") {
                        Group {
                            usedBy.forEach { login ->
                                LinkRow(leading = { ServiceIcon(login, 28.dp) }, title = login.title, onClick = { onOpen(login.id) })
                            }
                        }
                    }
                }

                if (item.phoneNumber.isNotEmpty() || item.pin.isNotEmpty()) {
                    Section("Account details") {
                        Group {
                            ValueRow("Phone number", item.phoneNumber)
                            ValueRow("PIN", item.pin, secret = true)
                        }
                    }
                }

                if (item.note.isNotBlank()) {
                    Section("Note") {
                        Card {
                            SelectionContainer {
                                Text(item.note, SyncedPassTheme.type.body1, modifier = Modifier.padding(Spacing.l).fillMaxWidth())
                            }
                        }
                    }
                }

                if (item.customFields.isNotEmpty()) {
                    Section("Custom fields") {
                        Group {
                            item.customFields.forEach { field ->
                                when (field.kind) {
                                    CustomField.Kind.Date -> ValueRow(field.name, formatDate(field.date, FormatStyle.MEDIUM), copyable = false)
                                    CustomField.Kind.Text -> ValueRow(field.name, field.value)
                                    else -> ValueRow(field.name, field.value, secret = true)
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(horizontal = Spacing.xs)) {
                    Text("Created ${formatDateTime(item.createdAt)}", SyncedPassTheme.type.caption, color = c.textWeak)
                    Text("Modified ${formatDateTime(item.modifiedAt)}", SyncedPassTheme.type.caption, color = c.textWeak)
                }
            }
        }
    } }
}

/** A titled group of rows. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(title)
        content()
    }
}

/** A card holding rows. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Card { Column(content = content) }
}

/**
 * A label above its value, with copy (and show/hide for secrets). Renders
 * nothing for an empty value, so only what the login has is shown.
 */
@Composable
private fun ValueRow(label: String, value: String, secret: Boolean = false, copyable: Boolean = true) {
    if (value.isEmpty()) return
    val c = SyncedPassTheme.colors
    var revealed by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, SyncedPassTheme.type.label, color = c.textWeak)
            Spacer(Modifier.height(2.dp))
            if (secret && !revealed) {
                Text("•".repeat(10), SyncedPassTheme.type.body1)
            } else {
                SelectionContainer { Text(value, if (secret) SyncedPassTheme.type.mono else SyncedPassTheme.type.body1) }
            }
        }
        if (secret) {
            SmallIconButton(
                if (revealed) R.drawable.ph_eye_slash_regular else R.drawable.ph_eye_regular,
                if (revealed) "Hide $label" else "Show $label",
            ) { revealed = !revealed }
        }
        if (copyable) CopyButton(label, value)
    }
}

@Composable
private fun WebsiteRow(website: String) {
    val context = LocalContext.current
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website))) }
            }
            .padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
    ) {
        Text(website, SyncedPassTheme.type.body1, color = c.accent, modifier = Modifier.weight(1f), maxLines = 2)
        Icon(R.drawable.ph_arrow_square_out_regular, null, size = 20.dp, tint = c.textWeak)
        Spacer(Modifier.width(Spacing.s))
        CopyButton("Website", website)
    }
}

/** A row that opens another login, or plain text when there's nothing to open. */
@Composable
private fun LinkRow(leading: @Composable () -> Unit, title: String, supporting: String? = null, onClick: (() -> Unit)?) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, SyncedPassTheme.type.body1)
            if (supporting != null) Text(supporting, SyncedPassTheme.type.body2, color = c.textWeak, maxLines = 1)
        }
        if (onClick != null) Icon(R.drawable.ph_caret_right_regular, null, size = 18.dp, tint = c.textWeak)
    }
}

/**
 * Copies [value] (see [Clipboard]). The icon turns into a check for a moment.
 */
@Composable
private fun CopyButton(label: String, value: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    SmallIconButton(if (copied) R.drawable.ph_check_regular else R.drawable.ph_copy_regular, "Copy $label",
                    tint = if (copied) SyncedPassTheme.colors.success else SyncedPassTheme.colors.textWeak) {
        Clipboard.copy(context, label, value)
        copied = true
    }
}

@Composable
private fun SmallIconButton(@DrawableRes icon: Int, description: String, tint: androidx.compose.ui.graphics.Color = SyncedPassTheme.colors.textWeak, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, null, size = 22.dp, tint = tint)
    }
}

private fun formatDate(date: ReferenceDate, style: FormatStyle): String =
    date.instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(style))

private fun formatDateTime(date: ReferenceDate): String =
    date.instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
