package com.abdurahmanharouat.syncedpass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.crypto.VaultCrypto
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.LabeledField
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.PrimaryButton
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme

@Composable
fun SetupScreen(busy: Boolean, error: String?, onCreate: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val problem = when {
        password.length < VaultCrypto.MINIMUM_PASSWORD_LENGTH -> "Use at least ${VaultCrypto.MINIMUM_PASSWORD_LENGTH} characters."
        confirmation != password -> "The passwords don't match."
        else -> null
    }
    LockLayout(
        title = "Create your vault",
        message = "Choose a master password. It encrypts everything you save in SyncedPass.",
    ) {
        LabeledField("Master password", password, { password = it }, secret = true)
        LabeledField(
            "Confirm master password", confirmation, { confirmation = it }, secret = true,
            error = error ?: problem.takeIf { confirmation.isNotEmpty() },
            imeAction = ImeAction.Done, onDone = { if (problem == null && !busy) onCreate(password) },
        )
        Row(verticalAlignment = Alignment.Top) {
            Icon(R.drawable.ph_warning_circle_regular, null, size = 20.dp, tint = SyncedPassTheme.colors.warning)
            Spacer(Modifier.width(Spacing.s))
            Text(
                "There's no way to recover a forgotten master password. Export a backup regularly.",
                SyncedPassTheme.type.body2, color = SyncedPassTheme.colors.textWeak,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton(if (busy) "Creating…" else "Create vault", onClick = { onCreate(password) }, enabled = problem == null && !busy)
    }
}

@Composable
fun UnlockScreen(busy: Boolean, error: String?, onUnlock: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    LockLayout(title = "SyncedPass is locked", message = "Enter your master password to unlock.") {
        LabeledField(
            "Master password", password, { password = it }, secret = true, error = error,
            imeAction = ImeAction.Go, onDone = { if (password.isNotEmpty() && !busy) onUnlock(password) },
        )
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton(if (busy) "Unlocking…" else "Unlock", onClick = { onUnlock(password) }, enabled = password.isNotEmpty() && !busy)
    }
}

/** The app icon, title and message above a column of fields; scrolls with the keyboard open. */
@Composable
private fun LockLayout(title: String, message: String, content: @Composable () -> Unit) {
    Page {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.huge),
        ) {
            Image(painterResource(R.drawable.padlock), contentDescription = null, modifier = Modifier.size(112.dp))
            Spacer(Modifier.height(Spacing.xl))
            Text(title, SyncedPassTheme.type.subtitle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.s))
            Text(message, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xxl))
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            ) { content() }
        }
    }
}
