package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchparty.WatchPartyConnectionState
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyParticipantStatus
import com.nuvio.app.features.watchparty.WatchPartyRoomCodes
import com.nuvio.app.features.watchparty.WatchPartySessionState
import com.nuvio.app.features.watchparty.WatchPartySupabaseProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

@Composable
internal fun PlayerScreenRuntime.RenderWatchPartyOverlays() {
    LaunchedEffect(showWatchPartyPanel) {
        if (showWatchPartyPanel && watchPartyDisplayName.isBlank()) {
            val profileName = ProfileRepository.state.value.activeProfile?.name?.takeIf { it.isNotBlank() }
            watchPartyDisplayName = profileName
                ?: getString(Res.string.watch_party_guest_name, Random.nextInt(1000, 10000))
        }
    }

    WatchPartyBadge(
        sessionState = watchPartySessionState,
        onClick = {
            showWatchPartyPanel = true
            controlsVisible = true
        },
    )

    PlayerWatchPartyPanel(
        visible = showWatchPartyPanel,
        sessionState = watchPartySessionState,
        isConfigured = WatchPartySupabaseProvider.isConfigured,
        displayName = watchPartyDisplayName,
        onDisplayNameChange = { watchPartyDisplayName = it },
        onCreateRoom = { createWatchPartyRoom() },
        onJoinRoom = { code -> joinWatchPartyRoom(code) },
        onLeave = { leaveWatchParty() },
        onDismiss = { showWatchPartyPanel = false },
    )
}

@Composable
private fun WatchPartyBadge(
    sessionState: WatchPartySessionState,
    onClick: () -> Unit,
) {
    if (!sessionState.isActive) return
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = stringResource(Res.string.watch_party_panel_title),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (sessionState.connection == WatchPartyConnectionState.CONNECTED) {
                        sessionState.participants.size.toString()
                    } else {
                        stringResource(Res.string.watch_party_reconnecting)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun PlayerWatchPartyPanel(
    visible: Boolean,
    sessionState: WatchPartySessionState,
    isConfigured: Boolean,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var codeInput by remember { mutableStateOf("") }
    LaunchedEffect(visible) {
        if (!visible) codeInput = ""
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)),
        exit = fadeOut(tween(NuvioTokens.Motion.normalMillis)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(tokens.colors.overlayScrim.copy(alpha = tokens.opacity.medium)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(NuvioTokens.Motion.sheetEnterMillis)) { it / 3 } +
                    fadeIn(tween(NuvioTokens.Motion.sheetEnterMillis)),
                exit = slideOutVertically(tween(NuvioTokens.Motion.sheetExitMillis)) { it / 3 } +
                    fadeOut(tween(NuvioTokens.Motion.sheetExitMillis)),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.92f)
                        .clip(tokens.shapes.playerPanel)
                        .background(tokens.colors.surfaceSheet)
                        .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.playerPanel)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                ) {
                    Column(modifier = Modifier.padding(tokens.spacing.sheetPadding)) {
                        Text(
                            text = stringResource(Res.string.watch_party_panel_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        when {
                            !isConfigured -> Text(
                                text = stringResource(Res.string.watch_party_not_configured),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            !sessionState.isActive -> {
                                OutlinedTextField(
                                    value = displayName,
                                    onValueChange = onDisplayNameChange,
                                    label = { Text(stringResource(Res.string.watch_party_your_name)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onCreateRoom,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.watch_party_create_room))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = codeInput,
                                        onValueChange = { codeInput = WatchPartyRoomCodes.normalize(it).take(WatchPartyRoomCodes.LENGTH) },
                                        label = { Text(stringResource(Res.string.watch_party_room_code)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        onClick = { onJoinRoom(codeInput) },
                                        enabled = WatchPartyRoomCodes.isValid(codeInput),
                                    ) {
                                        Text(stringResource(Res.string.watch_party_join_room))
                                    }
                                }
                            }
                            else -> {
                                Text(
                                    text = sessionState.roomCode.orEmpty(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(Res.string.watch_party_participants),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (sessionState.participants.size <= 1) {
                                    Text(
                                        text = stringResource(Res.string.watch_party_alone_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                                    items(sessionState.participants, key = { it.id }) { participant ->
                                        WatchPartyParticipantRow(participant)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        onLeave()
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(Res.string.watch_party_leave_room))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchPartyParticipantRow(participant: WatchPartyParticipant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (icon, description) = when (participant.status) {
            WatchPartyParticipantStatus.PLAYING ->
                Icons.Rounded.PlayArrow to stringResource(Res.string.watch_party_status_playing)
            WatchPartyParticipantStatus.PAUSED ->
                Icons.Rounded.Pause to stringResource(Res.string.watch_party_status_paused)
            WatchPartyParticipantStatus.BUFFERING ->
                Icons.Rounded.HourglassTop to stringResource(Res.string.watch_party_status_buffering)
            WatchPartyParticipantStatus.SELECTING_SOURCE ->
                Icons.Rounded.Search to stringResource(Res.string.watch_party_status_selecting_source)
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = participant.displayName,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
