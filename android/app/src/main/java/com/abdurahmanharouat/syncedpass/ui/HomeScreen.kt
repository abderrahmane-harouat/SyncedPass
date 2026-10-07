package com.abdurahmanharouat.syncedpass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.ui.components.Card
import com.abdurahmanharouat.syncedpass.ui.components.CircleIconButton
import com.abdurahmanharouat.syncedpass.ui.components.EmptyState
import com.abdurahmanharouat.syncedpass.ui.components.FloatingAddButton
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.ServiceIcon
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.PrimaryButton
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme

/**
 * Home, laid out like Proton Authenticator's: a large title with a round
 * button on the right, cards below, and the search bar and add button fixed
 * at the bottom, within thumb reach. The list scrolls; the bars don't.
 */
@Composable
fun HomeScreen(
    items: List<LoginItem>,
    search: (String) -> List<LoginItem>,
    /** Kept by the caller, so the search and scroll position survive opening a login and coming back. */
    query: String,
    onQuery: (String) -> Unit,
    listState: LazyListState,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onNew: () -> Unit,
    onOpen: (LoginItem) -> Unit,
) {
    val results = search(query)
    Page {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl, bottom = Spacing.l),
            ) {
                Text("SyncedPass", SyncedPassTheme.type.title, modifier = Modifier.weight(1f))
                CircleIconButton(R.drawable.ph_gear_six_regular, "Settings", onSettings)
                Spacer(Modifier.width(Spacing.s))
                CircleIconButton(R.drawable.ph_lock_simple_regular, "Lock", onLock)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    items.isEmpty() -> EmptyState(
                        illustration = R.drawable.ph_password_regular,
                        headline = "No logins yet",
                        body = "Save your first login. It stays encrypted on this phone.",
                        modifier = Modifier.align(Alignment.Center),
                    ) { PrimaryButton("New login", onNew) }
                    results.isEmpty() -> EmptyState(
                        illustration = R.drawable.ph_magnifying_glass_regular,
                        headline = "No results",
                        body = "Nothing matches “$query”.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results, key = { it.id }) { item -> LoginRow(item) { onOpen(item) } }
                    }
                }
            }

            if (items.isNotEmpty()) {
                BottomBar(query, onQuery, onNew)
            }
        }
    }
}

@Composable
private fun LoginRow(item: LoginItem, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(Spacing.l)) {
            ServiceIcon(item)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(item.title, SyncedPassTheme.type.body1Medium, maxLines = 1)
                if (item.subtitle.isNotEmpty()) Text(item.subtitle, SyncedPassTheme.type.body2, color = SyncedPassTheme.colors.textWeak, maxLines = 1)
            }
        }
    }
}

/** Search pill and add button, pinned to the bottom of the screen. */
@Composable
private fun BottomBar(query: String, onQuery: (String) -> Unit, onNew: () -> Unit) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(c.bottomBar).padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).clip(CircleShape).background(c.inputFill).padding(start = Spacing.l, end = Spacing.xs),
        ) {
            Icon(R.drawable.ph_magnifying_glass_regular, null, size = 20.dp, tint = c.textWeak)
            Spacer(Modifier.width(Spacing.m))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search", SyncedPassTheme.type.body1, color = c.textPlaceholder)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = SyncedPassTheme.type.body1.copy(color = c.text),
                    cursorBrush = SolidColor(c.primaryBottom),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search logins" },
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onQuery("") }.semantics { contentDescription = "Clear search" },
                ) { Icon(R.drawable.ph_x_regular, null, size = 18.dp, tint = c.textWeak) }
            } else {
                Spacer(Modifier.width(Spacing.m))
            }
        }
        Spacer(Modifier.width(Spacing.m))
        FloatingAddButton("New login", onNew)
    }
}
