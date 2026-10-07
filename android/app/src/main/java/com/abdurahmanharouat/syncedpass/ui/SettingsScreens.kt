package com.abdurahmanharouat.syncedpass.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.BuildConfig
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.ui.components.BottomSheet
import com.abdurahmanharouat.syncedpass.ui.components.Card
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.IconButton
import com.abdurahmanharouat.syncedpass.ui.components.LabeledField
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.PrimaryButton
import com.abdurahmanharouat.syncedpass.ui.components.SecondaryButton
import com.abdurahmanharouat.syncedpass.ui.components.SectionHeader
import com.abdurahmanharouat.syncedpass.ui.components.SuccessCheckmark
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import kotlinx.coroutines.delay

/*
 * Settings, and the backup and master password flows started from it. Same
 * steps and wording as the Mac app (Views/BackupViews.swift and
 * ChangeMasterPasswordSheet); laid out like Proton Authenticator's settings.
 */

@Composable
fun SettingsScreen(
    importState: Operation,
    syncSummary: String,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissImportError: () -> Unit,
    onChangePassword: () -> Unit,
) {
    Page {
        SubScreen("Settings", onBack) {
            SectionHeader("Backup")
            SettingsGroup {
                SettingsRow(R.drawable.ph_export_regular, "Export backup", "Save an encrypted copy of your logins", onExport)
                SettingsDivider()
                SettingsRow(
                    R.drawable.ph_download_simple_regular, "Import backup",
                    if (importState == Operation.Working) "Opening…" else "Add logins from a SyncedPass backup", onImport,
                )
            }
            Spacer(Modifier.height(Spacing.s))
            SectionHeader("Sync")
            SettingsGroup {
                SettingsRow(R.drawable.ph_arrows_clockwise_regular, "Sync with Mac", syncSummary, onSync)
            }
            Spacer(Modifier.height(Spacing.s))
            SectionHeader("Security")
            SettingsGroup {
                SettingsRow(R.drawable.ph_key_regular, "Change master password", "Re-encrypt your vault with a new password", onChangePassword)
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                "SyncedPass ${BuildConfig.VERSION_NAME} · Works locally only, no cloud.",
                SyncedPassTheme.type.caption, color = SyncedPassTheme.colors.textWeak,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (importState is Operation.Failed) {
            BottomSheet("Can't import this file", onDismissImportError) {
                Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    Text(importState.message, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak)
                    PrimaryButton("OK", onDismissImportError)
                }
            }
        }
    }
}

@Composable
fun ExportBackupScreen(state: Operation, onExport: (String) -> Unit, onClose: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val working = state == Operation.Working
    val problem = when {
        password.length < VaultCrypto.MINIMUM_PASSWORD_LENGTH -> "Use at least ${VaultCrypto.MINIMUM_PASSWORD_LENGTH} characters."
        confirmation != password -> "The passwords don't match."
        else -> null
    }
    ClearWhenDone(state) { password = ""; confirmation = "" }

    Page {
        FormOrSuccess(
            title = "Export backup", onBack = onClose, succeeded = state is Operation.Succeeded,
            success = {
                SuccessContent("Backup exported", (state as? Operation.Succeeded)?.message.orEmpty()) {
                    PrimaryButton("Done", onClose)
                }
            },
        ) {
            Intro("Choose a password for this backup. You'll need it to restore — it can be different from your master password.")
            LabeledField("Backup password", password, { password = it }, secret = true)
            LabeledField(
                "Confirm backup password", confirmation, { confirmation = it }, secret = true,
                error = (state as? Operation.Failed)?.message ?: problem.takeIf { confirmation.isNotEmpty() },
                imeAction = ImeAction.Done, onDone = { if (problem == null && !working) onExport(password) },
            )
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(if (working) "Encrypting…" else "Export…", { onExport(password) }, enabled = problem == null && !working)
        }
    }
}

@Composable
fun ImportBackupScreen(state: Operation, onImport: (String) -> Unit, onClose: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    val working = state == Operation.Working
    ClearWhenDone(state) { password = "" }

    Page {
        FormOrSuccess(
            title = "Import backup", onBack = onClose, succeeded = state is Operation.Succeeded,
            success = {
                SuccessContent("Backup imported", (state as? Operation.Succeeded)?.message.orEmpty()) {
                    PrimaryButton("Done", onClose)
                }
            },
        ) {
            Intro("Enter the password that was chosen when this backup was exported. Logins already in your vault aren't duplicated; for the same login, the newer edit is kept.")
            LabeledField(
                "Backup password", password, { password = it }, secret = true,
                error = (state as? Operation.Failed)?.message,
                imeAction = ImeAction.Go, onDone = { if (password.isNotEmpty() && !working) onImport(password) },
            )
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(if (working) "Importing…" else "Import", { onImport(password) }, enabled = password.isNotEmpty() && !working)
        }
    }
}

@Composable
fun ChangeMasterPasswordScreen(
    state: Operation,
    onChange: (current: String, new: String) -> Unit,
    onExportBackup: () -> Unit,
    onClose: () -> Unit,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val working = state == Operation.Working
    val problem = when {
        current.isEmpty() -> ""
        new.length < VaultCrypto.MINIMUM_PASSWORD_LENGTH -> "Use at least ${VaultCrypto.MINIMUM_PASSWORD_LENGTH} characters."
        new == current -> "The new password is the same as the current one."
        confirmation != new -> "The new passwords don't match."
        else -> null
    }
    val failure = state as? Operation.Failed
    // A wrong current password is cleared so it can be typed again.
    LaunchedEffect(failure) { if (failure?.wrongPassword == true) current = "" }
    ClearWhenDone(state) { current = ""; new = ""; confirmation = "" }

    Page {
        FormOrSuccess(
            title = "Change master password", onBack = onClose, succeeded = state is Operation.Succeeded,
            success = {
                SuccessContent(
                    "Master password changed",
                    "Use your new password the next time you unlock. Export a fresh backup now so you have one protected by a password you'll remember.",
                ) {
                    PrimaryButton("Done", onClose)
                    SecondaryButton("Export backup", onExportBackup)
                }
            },
        ) {
            Intro("Your vault is re-encrypted with a new key. Backups you exported before keep the password you chose when exporting them.")
            LabeledField("Current master password", current, { current = it }, secret = true,
                         error = failure?.message?.takeIf { failure.wrongPassword })
            LabeledField("New master password", new, { new = it }, secret = true)
            LabeledField(
                "Confirm new master password", confirmation, { confirmation = it }, secret = true,
                error = failure?.message?.takeIf { !failure.wrongPassword } ?: problem?.takeIf { confirmation.isNotEmpty() && it.isNotEmpty() },
                imeAction = ImeAction.Done, onDone = { if (problem == null && !working) onChange(current, new) },
            )
            Row(verticalAlignment = Alignment.Top) {
                Icon(R.drawable.ph_warning_circle_regular, null, size = 20.dp, tint = SyncedPassTheme.colors.warning)
                Spacer(Modifier.width(Spacing.s))
                Text("There's no way to recover a forgotten master password. Export a backup after changing it.",
                     SyncedPassTheme.type.body2, color = SyncedPassTheme.colors.textWeak)
            }
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(if (working) "Changing…" else "Change password", { onChange(current, new) }, enabled = problem == null && !working)
        }
    }
}

/** Back arrow and title fixed at the top, the rest scrolls. */
@Composable
internal fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.s),
        ) {
            IconButton(R.drawable.ph_arrow_left_regular, "Back", onBack)
            Text(title, SyncedPassTheme.type.headline)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xxl),
            content = content,
        )
    }
}

/**
 * The form, then the success screen once it worked. One after the other, not
 * a cross-fade: the form fades out quickly, then the success screen fades in.
 */
@Composable
private fun FormOrSuccess(
    title: String,
    onBack: () -> Unit,
    succeeded: Boolean,
    success: @Composable () -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    AnimatedContent(
        targetState = succeeded,
        transitionSpec = { fadeIn(tween(200, delayMillis = 120)) togetherWith fadeOut(tween(120)) },
        label = "form or success",
    ) { done ->
        if (done) {
            BackHandler(onBack = onBack)
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) { success() }
        } else {
            SubScreen(title, onBack) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.l), modifier = Modifier.widthIn(max = 560.dp)) { form() }
            }
        }
    }
}

@Composable
internal fun SuccessContent(title: String, message: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl).widthIn(max = 480.dp),
    ) {
        SuccessCheckmark()
        Spacer(Modifier.height(Spacing.xl))
        Text(title, SyncedPassTheme.type.subtitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(message, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xxl))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), content = actions)
    }
}

/**
 * Empties the password fields once they're no longer needed, after the form
 * has faded out so the fields aren't seen emptying.
 */
@Composable
private fun ClearWhenDone(state: Operation, clear: () -> Unit) {
    LaunchedEffect(state is Operation.Succeeded) {
        if (state is Operation.Succeeded) { delay(400); clear() }
    }
}

@Composable
internal fun Intro(text: String) {
    Text(text, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak)
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card { Column(content = content) }
}

@Composable
internal fun SettingsRow(@DrawableRes icon: Int, title: String, supporting: String, onClick: () -> Unit) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Icon(icon, null, tint = c.text)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, SyncedPassTheme.type.body1Medium)
            Text(supporting, SyncedPassTheme.type.body2, color = c.textWeak)
        }
        Icon(R.drawable.ph_caret_right_regular, null, size = 18.dp, tint = c.textWeak)
    }
}

@Composable
internal fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(SyncedPassTheme.colors.cardBorder))
}
