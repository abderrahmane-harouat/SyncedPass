package com.abdurahmanharouat.syncedpass.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.abdurahmanharouat.syncedpass.R
import com.abdurahmanharouat.syncedpass.model.KnownService
import com.abdurahmanharouat.syncedpass.model.LoginItem
import com.abdurahmanharouat.syncedpass.model.SignInMethod
import com.abdurahmanharouat.syncedpass.model.service
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme

/**
 * The square icon for a login, as on the Mac (Views/ServiceIcon.swift): the
 * bundled service logo when the login matches a known service, otherwise its
 * first letter. Logins signed into through a provider ("Sign in with Google")
 * carry that provider's logo as a small badge in the top-left corner.
 */
@Composable
fun ServiceIcon(item: LoginItem, size: Dp = 40.dp, ringColor: Color = SyncedPassTheme.colors.card) {
    val service = KnownService.matching(item)
    val provider = item.signIns.firstNotNullOfOrNull { it.method.service }
    // No badge on the provider's own logins (a Google login that lists "Sign in with Google").
    ServiceIcon(item.title, service, size, badge = provider.takeIf { it != service }, ringColor = ringColor)
}

@Composable
fun ServiceIcon(
    title: String,
    service: KnownService?,
    size: Dp = 40.dp,
    badge: KnownService? = null,
    ringColor: Color = SyncedPassTheme.colors.card,
) {
    val shape = RoundedCornerShape(size * 0.24f)
    val rgb = service?.color ?: fallbackColor(title)
    val brand = Color(0xFF000000.toInt() or rgb)
    val whiteTile = service?.logo == KnownService.Logo.Color || service?.logo == KnownService.Logo.AppIcon
    // Black on light brand colors (e.g. Snapchat yellow), white otherwise.
    val luminance = 0.299f * brand.red + 0.587f * brand.green + 0.114f * brand.blue
    val foreground = if (luminance > 0.7f) Color.Black else Color.White
    val logo = service?.logoRes

    Box(Modifier.size(size)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (whiteTile) Color.White else brand)
                .border(1.dp, SyncedPassTheme.colors.cardBorder, shape),
        ) {
            when {
                logo != null && service.logo == KnownService.Logo.Monochrome ->
                    Image(painterResource(logo), null, Modifier.fillMaxSize().padding(size * 0.2f), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(foreground))
                logo != null && service.logo == KnownService.Logo.Color ->
                    Image(painterResource(logo), null, Modifier.fillMaxSize().padding(size * 0.18f), contentScale = ContentScale.Fit)
                logo != null && service.logo == KnownService.Logo.AppIcon ->
                    Image(painterResource(logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> {
                    val initial = (service?.name ?: title).trim().firstOrNull()?.uppercase() ?: "?"
                    val fontSize = with(LocalDensity.current) { (size * 0.46f).toSp() }
                    Text(initial, TextStyle(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold), color = foreground)
                }
            }
        }
        if (badge != null) {
            val badgeSize = max(size * 0.46f, 12.dp)
            val ring = max(size * 0.05f, 1.5.dp)
            // A ring in the surrounding color keeps the badge readable on any logo underneath.
            Box(
                Modifier
                    .offset(-size * 0.16f, -size * 0.16f)
                    .background(ringColor, RoundedCornerShape((badgeSize + ring * 2) * 0.26f))
                    .padding(ring),
            ) {
                ServiceIcon(badge.name, badge, badgeSize)
            }
        }
    }
}

/** The sign-in method's service logo as a small tile, or a key for plain password sign-in. */
@Composable
fun SignInMethodIcon(method: SignInMethod, size: Dp = 24.dp) {
    val service = method.service
    if (service != null) ServiceIcon(service.name, service, size)
    else Icon(R.drawable.ph_key_regular, null, size = size, tint = SyncedPassTheme.colors.textWeak)
}

/**
 * A stable color per title, so unknown logins keep the same color between
 * launches. Same palette and formula as the Mac.
 */
private fun fallbackColor(title: String): Int {
    val palette = intArrayOf(0x5B6CFF, 0x00A884, 0xE5484D, 0xF5A524, 0x8E4EC6, 0x0091FF, 0xD6409F, 0x12A594)
    val sum = title.codePoints().toArray().fold(0) { acc, cp -> acc + cp }
    return palette[Math.floorMod(sum, palette.size)]
}
