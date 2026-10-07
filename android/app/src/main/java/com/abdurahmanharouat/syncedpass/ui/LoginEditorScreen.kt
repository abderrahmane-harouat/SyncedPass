package com.abdurahmanharouat.syncedpass.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.model.CustomField
import com.abdurahmanharouat.syncedpass.model.KnownService
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.LoginValidation
import com.abdurahmanharouat.syncedpass.model.ReferenceDate
import com.abdurahmanharouat.syncedpass.model.SignIn
import com.abdurahmanharouat.syncedpass.model.SignInMethod
import com.abdurahmanharouat.syncedpass.model.accountCandidates
import com.abdurahmanharouat.syncedpass.model.accountChoiceLabel
import com.abdurahmanharouat.syncedpass.model.changingService
import com.abdurahmanharouat.syncedpass.model.cleanedForSaving
import com.abdurahmanharouat.syncedpass.model.validationErrors
import com.abdurahmanharouat.syncedpass.ui.components.AddButton
import com.abdurahmanharouat.syncedpass.ui.components.BottomSheet
import com.abdurahmanharouat.syncedpass.ui.components.Card
import com.abdurahmanharouat.syncedpass.ui.components.FootnoteText
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.IconButton
import com.abdurahmanharouat.syncedpass.ui.components.LabeledField
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.RemoveButton
import com.abdurahmanharouat.syncedpass.ui.components.SearchField
import com.abdurahmanharouat.syncedpass.ui.components.SecondaryButton
import com.abdurahmanharouat.syncedpass.ui.components.SectionHeader
import com.abdurahmanharouat.syncedpass.ui.components.SelectField
import com.abdurahmanharouat.syncedpass.ui.components.ServiceIcon
import com.abdurahmanharouat.syncedpass.ui.components.SheetMessage
import com.abdurahmanharouat.syncedpass.ui.components.SheetRow
import com.abdurahmanharouat.syncedpass.ui.components.SignInMethodIcon
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Create or edit a login, with the same fields and sections as the Mac editor
 * (Views/LoginEditorView.swift): service, title, credentials, websites,
 * sign-in methods, account details, note and custom fields. Only the title is
 * required. Layout follows Proton Authenticator's form: close, title, Save.
 */
@Composable
fun LoginEditorScreen(
    isNew: Boolean,
    draft: LoginItem,
    onChange: (LoginItem) -> Unit,
    allItems: List<LoginItem>,
    onSave: (LoginItem) -> String?,
    onDelete: () -> String?,
    onClose: () -> Unit,
) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val c = SyncedPassTheme.colors
    val errors = draft.validationErrors
    val service = KnownService.matching(draft)

    BackHandler(onBack = onClose)

    fun save() {
        error = onSave(draft.cleanedForSaving())
        if (error == null) onClose()
    }

    Page {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // Fixed top bar, outside the scrolling form.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.s),
            ) {
                IconButton(R.drawable.ph_x_regular, "Close", onClose)
                Text(if (isNew) "New login" else "Edit login", SyncedPassTheme.type.headline, modifier = Modifier.weight(1f))
                val canSave = errors.isEmpty()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = canSave, role = Role.Button, onClick = ::save)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                ) {
                    Text("Save", SyncedPassTheme.type.button, color = if (canSave) c.accent else c.textPlaceholder)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xxl),
            ) {
                Section(null) {
                    SelectField("Service", onClick = { sheet = Sheet.Service }) {
                        if (service != null) {
                            ServiceIcon(service.name, service, 28.dp)
                            Spacer(Modifier.width(Spacing.m))
                            Text(service.name, SyncedPassTheme.type.body1, maxLines = 1)
                        } else {
                            Text("Choose…", SyncedPassTheme.type.body1, color = c.textPlaceholder)
                        }
                    }
                    // An empty title is already signalled by the asterisk and the
                    // disabled Save button; only call out whitespace-only titles.
                    LabeledField("Title", draft.title, { onChange(draft.copy(title = it)) }, placeholder = "e.g. GitHub", required = true,
                                 error = if (draft.title.isNotEmpty() && draft.title.isBlank()) "Title can't be only spaces." else null)
                    FootnoteText("Fields marked * are required. Everything else is optional.")
                }

                Section("Credentials") {
                    LabeledField("Email", draft.email, { onChange(draft.copy(email = it)) }, placeholder = "name@example.com",
                                 keyboardType = KeyboardType.Email, error = LoginValidation.emailError(draft.email))
                    LabeledField("Username", draft.username, { onChange(draft.copy(username = it)) })
                    LabeledField("Password", draft.password, { onChange(draft.copy(password = it)) }, secret = true)
                    LabeledField("2FA secret (TOTP)", draft.totpSecret, { onChange(draft.copy(totpSecret = it)) }, secret = true,
                                 placeholder = "Setup key or otpauth:// link", error = LoginValidation.totpError(draft.totpSecret))
                }

                Section("Websites") {
                    draft.websites.forEachIndexed { index, website ->
                        key(index) {
                            LabeledField(
                                "Website ${index + 1}", website,
                                { value -> onChange(draft.copy(websites = draft.websites.toMutableList().also { it[index] = value })) },
                                placeholder = "example.com", keyboardType = KeyboardType.Uri, showLabel = false,
                                error = LoginValidation.websiteError(website),
                                trailing = if (draft.websites.size > 1) {
                                    { RemoveButton("Remove website") { onChange(draft.copy(websites = draft.websites.filterIndexed { i, _ -> i != index })) } }
                                } else null,
                            )
                        }
                    }
                    AddButton("Add website", { onChange(draft.copy(websites = draft.websites + "")) })
                }

                Section("Sign-in methods") {
                    draft.signIns.forEach { signIn ->
                        key(signIn.method) {
                            SignInRow(signIn, allItems, onAccount = { sheet = Sheet.Account(signIn.method) }) {
                                onChange(draft.copy(signIns = draft.signIns.filter { it.method != signIn.method }))
                            }
                        }
                    }
                    if (SignInMethod.selectable.any { m -> draft.signIns.none { it.method == m } }) {
                        AddButton("Add sign-in method", { sheet = Sheet.AddSignIn })
                    }
                    FootnoteText("Add every way into this account, e.g. Sign in with Google and a password, and pick which Google account it uses.")
                }

                Section("Account details") {
                    LabeledField("Phone number", draft.phoneNumber, { onChange(draft.copy(phoneNumber = it)) },
                                 placeholder = "e.g. +213 555 12 34 56", keyboardType = KeyboardType.Phone)
                    LabeledField("PIN", draft.pin, { onChange(draft.copy(pin = it)) }, secret = true, keyboardType = KeyboardType.NumberPassword)
                }

                Section("Note") {
                    LabeledField("Note", draft.note, { onChange(draft.copy(note = it)) }, showLabel = false,
                                 singleLine = false, minLines = 5, imeAction = ImeAction.Default)
                }

                Section("Custom fields") {
                    draft.customFields.forEach { field ->
                        key(field.id) {
                            CustomFieldCard(
                                field,
                                onChange = { updated -> onChange(draft.copy(customFields = draft.customFields.map { if (it.id == field.id) updated else it })) },
                                onRemove = { onChange(draft.copy(customFields = draft.customFields.filter { it.id != field.id })) },
                            )
                        }
                    }
                    AddButton("Add field", { sheet = Sheet.AddField })
                }

                error?.let { Text("$it Your changes are still here; try again.", SyncedPassTheme.type.body2, color = c.error) }

                if (!isNew) {
                    // Two taps to delete: nothing destructive behind a single tap.
                    SecondaryButton(
                        if (confirmingDelete) "Tap again to delete “${draft.title}”" else "Delete login",
                        onClick = {
                            if (confirmingDelete) {
                                error = onDelete()
                                if (error == null) onClose()
                            } else confirmingDelete = true
                        },
                        color = c.error,
                    )
                }
            }
        }

        when (val open = sheet) {
            null -> {}
            Sheet.Service -> ServiceSheet(service, onDismiss = { sheet = null }) { picked ->
                val updated = draft.changingService(picked)
                onChange(if (updated.websites.isEmpty()) updated.copy(websites = listOf("")) else updated)
                sheet = null
            }
            Sheet.AddSignIn -> BottomSheet("Add sign-in method", onDismiss = { sheet = null }) {
                LazyColumn {
                    items(SignInMethod.selectable.filter { m -> draft.signIns.none { it.method == m } }) { method ->
                        SheetRow(method.displayName, onClick = {
                            onChange(draft.copy(signIns = draft.signIns + SignIn(method)))
                            sheet = null
                        }, leading = { SignInMethodIcon(method, 28.dp) })
                    }
                }
            }
            is Sheet.Account -> AccountSheet(
                method = open.method,
                selection = draft.signIns.firstOrNull { it.method == open.method }?.accountID,
                candidates = allItems.accountCandidates(open.method, excluding = draft.id),
                allItems = allItems,
                onDismiss = { sheet = null },
            ) { account ->
                onChange(draft.copy(signIns = draft.signIns.map { if (it.method == open.method) it.copy(accountID = account) else it }))
                sheet = null
            }
            Sheet.AddField -> BottomSheet("Add field", onDismiss = { sheet = null }) {
                CustomField.Kind.entries.forEach { kind ->
                    SheetRow(kind.displayName, onClick = {
                        onChange(draft.copy(customFields = draft.customFields + CustomField(kind = kind)))
                        sheet = null
                    }, leading = { Icon(kind.icon, null, tint = c.text) })
                }
            }
        }
    }
}

/** Which bottom sheet is open. Saveable, so it survives rotation. */
private sealed interface Sheet : java.io.Serializable {
    data object Service : Sheet
    data object AddSignIn : Sheet
    data object AddField : Sheet
    data class Account(val method: SignInMethod) : Sheet
}

/** A group of fields under a small uppercase header. */
@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (title != null) SectionHeader(title)
        content()
    }
}

private val CustomField.Kind.icon: Int
    get() = when (this) {
        CustomField.Kind.Text -> R.drawable.ph_text_t_regular
        CustomField.Kind.Hidden -> R.drawable.ph_eye_slash_regular
        CustomField.Kind.Totp -> R.drawable.ph_clock_countdown_regular
        CustomField.Kind.Date -> R.drawable.ph_calendar_blank_regular
    }

/** One sign-in method: its logo and name, and for providers the account it uses. */
@Composable
private fun SignInRow(signIn: SignIn, allItems: List<LoginItem>, onAccount: () -> Unit, onRemove: () -> Unit) {
    val c = SyncedPassTheme.colors
    Card {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = if (signIn.method.isProvider) Spacing.l else Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignInMethodIcon(signIn.method, 28.dp)
                Spacer(Modifier.width(Spacing.m))
                Text(signIn.method.displayName, SyncedPassTheme.type.body1Medium, modifier = Modifier.weight(1f))
                RemoveButton("Remove ${signIn.method.displayName}", onRemove)
            }
            if (signIn.method.isProvider) {
                Spacer(Modifier.height(Spacing.s))
                Box(Modifier.padding(end = Spacing.m)) {
                    SelectField("${signIn.method.providerName} account", onClick = onAccount) {
                        val account = signIn.accountID?.let { id -> allItems.firstOrNull { it.id == id } }
                        when {
                            account != null -> Text(account.accountChoiceLabel, SyncedPassTheme.type.body1, maxLines = 1)
                            signIn.accountID != null -> Text("Deleted login", SyncedPassTheme.type.body1, color = c.textWeak)
                            else -> Text("Not specified", SyncedPassTheme.type.body1, color = c.textPlaceholder)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomFieldCard(field: CustomField, onChange: (CustomField) -> Unit, onRemove: () -> Unit) {
    val c = SyncedPassTheme.colors
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                Icon(field.kind.icon, null, size = 18.dp, tint = c.textWeak)
                Spacer(Modifier.width(Spacing.s))
                Text(field.kind.displayName, SyncedPassTheme.type.label, color = c.textWeak, modifier = Modifier.weight(1f))
                RemoveButton("Remove field", onRemove)
            }
            LabeledField("Name", field.name, { onChange(field.copy(name = it)) }, placeholder = "e.g. Recovery email", required = true,
                         error = if (field.name.isBlank()) "Field name is required." else null)
            when (field.kind) {
                CustomField.Kind.Text -> LabeledField("Value", field.value, { onChange(field.copy(value = it)) })
                CustomField.Kind.Hidden -> LabeledField("Value", field.value, { onChange(field.copy(value = it)) }, secret = true)
                CustomField.Kind.Totp -> LabeledField("Value", field.value, { onChange(field.copy(value = it)) }, secret = true,
                                                      placeholder = "Setup key or otpauth:// link", error = LoginValidation.totpError(field.value))
                CustomField.Kind.Date -> {
                    val zone = ZoneId.systemDefault()
                    val current = field.date.instant.atZone(zone)
                    SelectField("Date", onClick = {
                        DatePickerDialog(context, R.style.Theme_SyncedPass_DatePicker, { _, year, month, day ->
                            // Keep the time of day, like the Mac's date-only picker.
                            onChange(field.copy(date = ReferenceDate.of(current.with(LocalDate.of(year, month + 1, day)).toInstant())))
                        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                    }) {
                        Text(current.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), SyncedPassTheme.type.body1)
                    }
                }
            }
        }
    }
}

/** Searchable list of the bundled services, with "No service" to clear it. */
@Composable
private fun ServiceSheet(selected: KnownService?, onDismiss: () -> Unit, onPick: (KnownService?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = KnownService.search(query)
    Box(Modifier.fillMaxSize()) {
        BottomSheet("Service", onDismiss, fullHeight = true) {
            SearchField(query, { query = it }, "Search services", Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.s))
            if (results.isEmpty()) {
                SheetMessage("No services match “${query.trim()}”.")
            } else {
                LazyColumn {
                    if (selected != null && query.isBlank()) {
                        item { SheetRow("No service", onClick = { onPick(null) }, leading = { Icon(R.drawable.ph_x_regular, null) }) }
                    }
                    items(results, key = { it.id }) { service ->
                        SheetRow(service.name, onClick = { onPick(service) }, supporting = service.domains[0], selected = service == selected,
                                 leading = { ServiceIcon(service.name, service, 36.dp, ringColor = SyncedPassTheme.colors.pageBottom) })
                    }
                }
            }
        }
    }
}

/** Which saved login holds the account used for a provider, as on the Mac's AccountPicker. */
@Composable
private fun AccountSheet(
    method: SignInMethod,
    selection: java.util.UUID?,
    candidates: List<LoginItem>,
    allItems: List<LoginItem>,
    onDismiss: () -> Unit,
    onPick: (java.util.UUID?) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        BottomSheet("${method.providerName} account", onDismiss) {
            LazyColumn {
                item { SheetRow("Not specified", onClick = { onPick(null) }, selected = selection == null) }
                // Keep showing an existing link even if that login no longer
                // looks like an account (renamed, or deduplicated away).
                if (selection != null && candidates.none { it.id == selection }) {
                    val linked = allItems.firstOrNull { it.id == selection }
                    item {
                        SheetRow(linked?.accountChoiceLabel ?: "Deleted login", onClick = { onPick(selection) }, selected = true,
                                 leading = linked?.let { { ServiceIcon(it, 32.dp, ringColor = SyncedPassTheme.colors.pageBottom) } })
                    }
                }
                items(candidates, key = { it.id }) { item ->
                    SheetRow(item.accountChoiceLabel, onClick = { onPick(item.id) }, selected = item.id == selection,
                             leading = { ServiceIcon(item, 32.dp, ringColor = SyncedPassTheme.colors.pageBottom) })
                }
                if (candidates.isEmpty()) {
                    item {
                        FootnoteText(
                            "No ${method.providerName} accounts saved yet. Save the account itself as a login (e.g. “Gmail”) to link it here.",
                            Modifier.padding(Spacing.l),
                        )
                    }
                }
            }
        }
    }
}
