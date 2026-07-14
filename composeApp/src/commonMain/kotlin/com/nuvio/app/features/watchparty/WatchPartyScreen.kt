package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_alone_hint
import nuvio.composeapp.generated.resources.watch_party_create_room
import nuvio.composeapp.generated.resources.watch_party_join_room
import nuvio.composeapp.generated.resources.watch_party_leave_room
import nuvio.composeapp.generated.resources.watch_party_lobby_waiting
import nuvio.composeapp.generated.resources.watch_party_not_configured
import nuvio.composeapp.generated.resources.watch_party_now_watching
import nuvio.composeapp.generated.resources.watch_party_open_playback
import nuvio.composeapp.generated.resources.watch_party_panel_title
import nuvio.composeapp.generated.resources.watch_party_participants
import nuvio.composeapp.generated.resources.watch_party_reconnecting
import nuvio.composeapp.generated.resources.watch_party_rejoin_last
import nuvio.composeapp.generated.resources.watch_party_room_code
import nuvio.composeapp.generated.resources.watch_party_screen_title
import nuvio.composeapp.generated.resources.watch_party_your_name
import nuvio.composeapp.generated.resources.watch_party_status_buffering
import nuvio.composeapp.generated.resources.watch_party_status_idle
import nuvio.composeapp.generated.resources.watch_party_status_paused
import nuvio.composeapp.generated.resources.watch_party_status_playing
import nuvio.composeapp.generated.resources.watch_party_status_selecting_source
import org.jetbrains.compose.resources.stringResource

@Composable
fun WatchPartyScreen(
    modifier: Modifier = Modifier,
    onOpenPlayback: () -> Unit,
) {
    val sessionState by WatchPartyCoordinator.sessionState.collectAsStateWithLifecycle()
    val roomContent by WatchPartyCoordinator.roomContent.collectAsStateWithLifecycle()
    val lastRoomCode by WatchPartyCoordinator.lastRoomCode.collectAsStateWithLifecycle()
    var codeInput by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (nameInput.isBlank()) {
            nameInput = WatchPartyCoordinator.resolveDisplayName()
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            !sessionState.isActive -> WatchPartyJoinCreateSection(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(top = statusBarPadding + NuvioTokens.Space.s24, start = NuvioTokens.Space.s20, end = NuvioTokens.Space.s20),
                codeInput = codeInput,
                onCodeInputChange = { codeInput = it },
                nameInput = nameInput,
                onNameInputChange = { nameInput = it },
                lastRoomCode = lastRoomCode,
                isConfigured = WatchPartyCoordinator.isConfigured,
                onCreate = { WatchPartyCoordinator.createRoom(displayName = nameInput.takeIf { it.isNotBlank() }) },
                onJoin = { code -> WatchPartyCoordinator.joinRoom(code, displayName = nameInput.takeIf { it.isNotBlank() }) },
            )
            roomContent == null -> WatchPartyLobbySection(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(top = statusBarPadding + NuvioTokens.Space.s24, start = NuvioTokens.Space.s20, end = NuvioTokens.Space.s20),
                sessionState = sessionState,
                onLeave = { WatchPartyCoordinator.leave() },
            )
            else -> WatchPartyActiveSection(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(top = statusBarPadding + NuvioTokens.Space.s24, start = NuvioTokens.Space.s20, end = NuvioTokens.Space.s20),
                sessionState = sessionState,
                content = roomContent!!,
                onOpenPlayback = onOpenPlayback,
                onLeave = { WatchPartyCoordinator.leave() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Join / Create section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WatchPartyJoinCreateSection(
    modifier: Modifier = Modifier,
    codeInput: String,
    onCodeInputChange: (String) -> Unit,
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    lastRoomCode: String?,
    isConfigured: Boolean,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTokens.Icon.xl),
                    tint = tokens.colors.accent,
                )
                Text(
                    text = stringResource(Res.string.watch_party_screen_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                )
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
        }

        if (!isConfigured) {
            item {
                Surface(
                    color = tokens.colors.surface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(Res.string.watch_party_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(NuvioTokens.Space.s16),
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text(stringResource(Res.string.watch_party_your_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = tokens.colors.textPrimary,
                    unfocusedTextColor = tokens.colors.textPrimary,
                    focusedLabelColor = tokens.colors.accent,
                    unfocusedLabelColor = tokens.colors.textMuted,
                    focusedBorderColor = tokens.colors.accent,
                    unfocusedBorderColor = tokens.colors.borderDefault,
                    cursorColor = tokens.colors.accent,
                ),
            )
        }

        if (lastRoomCode != null) {
            item {
                OutlinedButton(
                    onClick = { onJoin(lastRoomCode) },
                    enabled = isConfigured,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.textPrimary),
                    border = BorderStroke(1.dp, tokens.colors.borderDefault),
                ) {
                    Text(text = stringResource(Res.string.watch_party_rejoin_last, lastRoomCode))
                }
            }
        }

        item {
            Button(
                onClick = onCreate,
                enabled = isConfigured,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                Text(text = stringResource(Res.string.watch_party_create_room))
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                val normalizedInput = WatchPartyRoomCodes.normalize(codeInput)
                val joinEnabled = isConfigured && WatchPartyRoomCodes.isValid(normalizedInput)

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = onCodeInputChange,
                    label = { Text(stringResource(Res.string.watch_party_room_code)) },
                singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.colors.textPrimary,
                        unfocusedTextColor = tokens.colors.textPrimary,
                        focusedLabelColor = tokens.colors.accent,
                        unfocusedLabelColor = tokens.colors.textMuted,
                        focusedBorderColor = tokens.colors.accent,
                        unfocusedBorderColor = tokens.colors.borderDefault,
                        cursorColor = tokens.colors.accent,
                    ),
                )
                Button(
                    onClick = { onJoin(normalizedInput) },
                    enabled = joinEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                    ),
                ) {
                    Text(text = stringResource(Res.string.watch_party_join_room))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lobby section (active session, no content yet)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WatchPartyLobbySection(
    modifier: Modifier = Modifier,
    sessionState: WatchPartySessionState,
    onLeave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                Text(
                    text = stringResource(Res.string.watch_party_panel_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                )
                if (sessionState.roomCode != null) {
                    Text(
                        text = sessionState.roomCode,
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.colors.accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
                if (sessionState.connection == WatchPartyConnectionState.CONNECTING) {
                    Text(
                        text = stringResource(Res.string.watch_party_reconnecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        item {
            Surface(
                color = tokens.colors.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(Res.string.watch_party_lobby_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NuvioTokens.Space.s16),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (sessionState.participants.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.watch_party_participants),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
            }
            items(sessionState.participants) { participant ->
                WatchPartyParticipantRow(participant = participant)
            }
        } else {
            item {
                Text(
                    text = stringResource(Res.string.watch_party_alone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.textPrimary),
                border = BorderStroke(1.dp, tokens.colors.borderDefault),
            ) {
                Text(text = stringResource(Res.string.watch_party_leave_room))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active section (session active with content)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WatchPartyActiveSection(
    modifier: Modifier = Modifier,
    sessionState: WatchPartySessionState,
    content: WatchPartyContentId,
    onOpenPlayback: () -> Unit,
    onLeave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                Text(
                    text = stringResource(Res.string.watch_party_panel_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                )
                if (sessionState.roomCode != null) {
                    Text(
                        text = sessionState.roomCode,
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.colors.accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
                if (sessionState.connection == WatchPartyConnectionState.CONNECTING) {
                    Text(
                        text = stringResource(Res.string.watch_party_reconnecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        item {
            Surface(
                color = tokens.colors.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NuvioTokens.Space.s16),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Text(
                        text = stringResource(Res.string.watch_party_now_watching, content.displayTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                    Button(
                        onClick = onOpenPlayback,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.colors.accent,
                            contentColor = tokens.colors.onAccent,
                        ),
                    ) {
                        Text(text = stringResource(Res.string.watch_party_open_playback))
                    }
                }
            }
        }

        if (sessionState.participants.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.watch_party_participants),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
            }
            items(sessionState.participants) { participant ->
                WatchPartyParticipantRow(participant = participant)
            }
        }

        item {
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.textPrimary),
                border = BorderStroke(1.dp, tokens.colors.borderDefault),
            ) {
                Text(text = stringResource(Res.string.watch_party_leave_room))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared participant row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WatchPartyParticipantRow(participant: WatchPartyParticipant) {
    val tokens = MaterialTheme.nuvio
    val (icon, description) = when (participant.status) {
        WatchPartyParticipantStatus.PLAYING ->
            Icons.Rounded.PlayArrow to stringResource(Res.string.watch_party_status_playing)
        WatchPartyParticipantStatus.PAUSED ->
            Icons.Rounded.Pause to stringResource(Res.string.watch_party_status_paused)
        WatchPartyParticipantStatus.BUFFERING ->
            Icons.Rounded.HourglassTop to stringResource(Res.string.watch_party_status_buffering)
        WatchPartyParticipantStatus.SELECTING_SOURCE ->
            Icons.Rounded.Search to stringResource(Res.string.watch_party_status_selecting_source)
        WatchPartyParticipantStatus.IDLE ->
            Icons.Rounded.Person to stringResource(Res.string.watch_party_status_idle)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = tokens.colors.textMuted,
        )
        Text(
            text = participant.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
        )
    }
}
