package com.abdurahmanharouat.syncedpass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.model.SyncPeer
import com.abdurahmanharouat.syncedpass.sync.SyncManager.PairingState
import com.abdurahmanharouat.syncedpass.sync.SyncManager.PeerState
import com.abdurahmanharouat.syncedpass.ui.components.Icon
import com.abdurahmanharouat.syncedpass.ui.components.Page
import com.abdurahmanharouat.syncedpass.ui.components.PrimaryButton
import com.abdurahmanharouat.syncedpass.ui.components.SecondaryButton
import com.abdurahmanharouat.syncedpass.ui.components.SectionHeader
import com.abdurahmanharouat.syncedpass.ui.components.Text
import com.abdurahmanharouat.syncedpass.ui.theme.Spacing
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.UUID

/*
 * Sync with the Mac (docs/SYNC.md): the paired Macs and their status, and
 * pairing a new one. Same steps and wording as the Mac's Sync window.
 */

@Composable
fun SyncScreen(
    deviceName: String,
    peers: List<SyncPeer>,
    states: Map<UUID, PeerState>,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onUnpair: (UUID) -> Unit,
) {
    var confirmingUnpair by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        SubScreen("Sync with Mac", onBack) {
            Intro("Your logins sync with your paired Mac over this network only, never through the internet, while SyncedPass is open and unlocked on both.")
            Spacer(Modifier.height(Spacing.s))
            SectionHeader("Paired Macs")
            if (peers.isEmpty()) {
                Text("No Mac paired yet.", SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak)
            } else {
                SettingsGroup {
                    peers.forEachIndexed { index, peer ->
                        if (index > 0) SettingsDivider()
                        PeerRow(peer, states[peer.deviceID], confirmingUnpair == peer.deviceID.toString()) {
                            if (confirmingUnpair == peer.deviceID.toString()) {
                                onUnpair(peer.deviceID)
                                confirmingUnpair = null
                            } else {
                                confirmingUnpair = peer.deviceID.toString()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.l))
            PrimaryButton(if (peers.isEmpty()) "Pair with a Mac" else "Pair another Mac", onPair)
            Spacer(Modifier.height(Spacing.s))
            Text("This phone: $deviceName", SyncedPassTheme.type.caption, color = SyncedPassTheme.colors.textWeak,
                 textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PeerRow(peer: SyncPeer, state: PeerState?, confirming: Boolean, onUnpair: () -> Unit) {
    val c = SyncedPassTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
    ) {
        Icon(R.drawable.ph_laptop_regular, null, tint = c.text)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(peer.name, SyncedPassTheme.type.body1Medium)
            Text(statusText(peer, state), SyncedPassTheme.type.body2, color = if (state is PeerState.Failed) c.error else c.textWeak)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onUnpair).padding(horizontal = Spacing.m),
        ) {
            Text(if (confirming) "Tap to confirm" else "Unpair", SyncedPassTheme.type.body1Medium, color = c.error)
        }
    }
}

@Composable
private fun statusText(peer: SyncPeer, state: PeerState?): String {
    // Re-render every 30 seconds so "synced 2 minutes ago" stays true.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return when (state) {
        is PeerState.Connected -> state.lastSynced?.let { "Connected · synced ${ago(it, now)}" } ?: "Connected"
        is PeerState.Failed -> state.message
        else -> "Waiting for ${peer.name} · open SyncedPass on the Mac, on this network"
    }
}

private fun ago(time: Instant, now: Long): String {
    val minutes = Duration.between(time, Instant.ofEpochMilli(now)).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "a minute ago"
        minutes < 60 -> "$minutes minutes ago"
        else -> "${minutes / 60} h ago"
    }
}

@Composable
fun PairMacScreen(
    state: PairingState?,
    deviceName: String,
    onConfirm: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Page {
        when (state) {
            null, PairingState.Ready -> SubScreen("Pair with a Mac", onClose) {
                Intro("On your Mac, open SyncedPass and choose SyncedPass › Sync with Phone… › Pair a Phone. Your Mac finds this phone and connects; then both show a code to compare. Both need to be on the same network.")
                Spacer(Modifier.height(Spacing.s))
                SectionHeader("This phone")
                SettingsGroup {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
                    ) {
                        Icon(R.drawable.ph_arrows_clockwise_regular, null, tint = SyncedPassTheme.colors.text)
                        Spacer(Modifier.width(Spacing.l))
                        Column(Modifier.weight(1f)) {
                            Text(deviceName, SyncedPassTheme.type.body1Medium)
                            Text("Ready to pair · waiting for your Mac…", SyncedPassTheme.type.body2, color = SyncedPassTheme.colors.textWeak)
                        }
                    }
                }
            }
            is PairingState.Confirming -> Centered(onClose) {
                CodeView("Pair with “${state.macName}”?", state.code)
                Text(
                    "Check that your Mac shows exactly the same code. If it doesn't, cancel: someone else may be trying to connect.",
                    SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.xxl))
                PrimaryButton("The codes match", { onConfirm(true) })
                Spacer(Modifier.height(Spacing.m))
                SecondaryButton("Cancel", { onConfirm(false) })
            }
            is PairingState.WaitingForMac -> Centered(onClose) {
                CodeView("Pair with “${state.macName}”?", state.code)
                Text("Waiting for you to confirm on the Mac…", SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.xxl))
                SecondaryButton("Cancel", onClose)
            }
            is PairingState.Paired -> {
                BackHandler(onBack = onClose)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                    SuccessContent(
                        "Paired with ${state.macName}",
                        "Your logins now sync automatically whenever SyncedPass is open and unlocked on both, on the same network.",
                    ) { PrimaryButton("Done", onClose) }
                }
            }
            is PairingState.Failed -> Centered(onClose) {
                Icon(R.drawable.ph_warning_circle_regular, null, size = 56.dp, tint = SyncedPassTheme.colors.warning)
                Spacer(Modifier.height(Spacing.l))
                Text("Pairing didn't finish", SyncedPassTheme.type.subtitle, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.s))
                Text(state.message, SyncedPassTheme.type.body1, color = SyncedPassTheme.colors.textWeak, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.xxl))
                PrimaryButton("Try again", onRetry)
                Spacer(Modifier.height(Spacing.m))
                SecondaryButton("Cancel", onClose)
            }
        }
    }
}

@Composable
private fun CodeView(title: String, code: String) {
    Text(title, SyncedPassTheme.type.subtitle, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Spacing.xl))
    Text(
        "${code.take(3)} ${code.drop(3)}",
        TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
        modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") },
    )
    Spacer(Modifier.height(Spacing.xl))
}

/** A centered column for the pairing steps; Back cancels. */
@Composable
private fun Centered(onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.xl).widthIn(max = 480.dp),
        ) { content() }
    }
}
