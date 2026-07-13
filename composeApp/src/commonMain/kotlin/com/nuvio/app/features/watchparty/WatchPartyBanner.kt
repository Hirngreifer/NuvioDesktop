package com.nuvio.app.features.watchparty

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioBottomNavigationBarInsets
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_banner_lobby
import nuvio.composeapp.generated.resources.watch_party_banner_watching
import org.jetbrains.compose.resources.stringResource

@Composable
fun WatchPartyBannerHost(
    isPlayerVisible: Boolean,
    onOpenTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionState by WatchPartyCoordinator.sessionState.collectAsStateWithLifecycle()
    val roomContent by WatchPartyCoordinator.roomContent.collectAsStateWithLifecycle()

    val show = sessionState.isActive && !isPlayerVisible

    val navBarBottom = nuvioBottomNavigationBarInsets()
        .asPaddingValues()
        .calculateBottomPadding()

    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it },
    ) {
        val content = roomContent
        val label = if (content == null) {
            stringResource(Res.string.watch_party_banner_lobby)
        } else {
            stringResource(Res.string.watch_party_banner_watching, content.displayTitle)
        }
        val onClick: () -> Unit = if (content == null) {
            onOpenTab
        } else {
            { WatchPartyCoordinator.requestManualFollow() }
        }

        WatchPartyBannerChip(
            label = label,
            onClick = onClick,
            modifier = Modifier.padding(bottom = navBarBottom + NuvioTokens.Space.s72),
        )
    }
}

@Composable
private fun WatchPartyBannerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .clip(tokens.shapes.chip)
                .background(tokens.colors.surfacePopover)
                .clickable(onClick = onClick)
                .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = NuvioTokens.Space.s6)
                    .size(NuvioTokens.Icon.sm),
                tint = tokens.colors.textPrimary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
