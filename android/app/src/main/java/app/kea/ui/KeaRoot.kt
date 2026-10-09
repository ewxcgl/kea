package app.kea.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kea.R
import app.kea.crypto.B64
import app.kea.crypto.Identity
import app.kea.crypto.InviteLink
import app.kea.data.Group
import app.kea.data.Member
import app.kea.data.Session
import app.kea.data.Settings
import app.kea.data.Store
import app.kea.data.Zone
import app.kea.service.ShareService
import kotlinx.coroutines.launch

private enum class Tab { MAP, ZONES, MEMBERS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeaRoot(
    store: Store,
    session: Session?,
    settings: Settings?,
    onRequestLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null means "not loaded yet"; showing a spinner beats flashing onboarding
    // at somebody who is already in a group.
    if (session == null || settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    var inviteDraft by rememberSaveable { mutableStateOf("") }

    // A zone being created or edited, held above both entry points so that a
    // map tap and the Zones tab produce the same editor.
    var zoneDraft by remember { mutableStateOf<Zone?>(null) }

    if (!session.hasGroup) {
        OnboardingScreen(
            settings = settings,
            inviteDraft = inviteDraft,
            onInviteChange = { inviteDraft = it },
            onCreate = { name ->
                scope.launch {
                    val identity = ensureIdentity(store)
                    val (groupId, groupKey) = InviteLink.newGroup()
                    val link = InviteLink.build(groupId, groupKey)
                    store.updateSession { s ->
                        s.copy(
                            group = Group(
                                groupId = B64.enc(groupId),
                                groupKey = B64.enc(groupKey),
                                name = name,
                                createdAt = System.currentTimeMillis(),
                                inviteLink = link,
                            ),
                            members = listOf(
                                Member(
                                    deviceId = identity.deviceId,
                                    name = name,
                                    publicKey = B64.enc(identity.publicKey),
                                    isSelf = true,
                                ),
                            ),
                        )
                    }
                    store.updateSettings { it.copy(displayName = name, onboardingDone = true) }
                    ShareService.start(context)
                }
            },
            onJoin = { draft ->
                val invite = runCatching { InviteLink.parse(draft) }.getOrNull()
                if (invite == null) {
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.onboarding_join_invalid))
                    }
                    return@OnboardingScreen
                }
                scope.launch {
                    val identity = ensureIdentity(store)
                    val name = settings.displayName
                    store.updateSession { s ->
                        s.copy(
                            group = Group(
                                groupId = B64.enc(invite.groupId),
                                groupKey = B64.enc(invite.groupKey),
                                name = "",
                                createdAt = System.currentTimeMillis(),
                                inviteLink = "kea://join#${B64.enc(invite.groupId + invite.groupKey)}",
                            ),
                            members = listOf(
                                Member(
                                    deviceId = identity.deviceId,
                                    name = name,
                                    publicKey = B64.enc(identity.publicKey),
                                    isSelf = true,
                                ),
                            ),
                        )
                    }
                    store.updateSettings { it.copy(onboardingDone = true) }
                    ShareService.start(context)
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            Tab.MAP -> stringResource(R.string.nav_map)
                            Tab.ZONES -> stringResource(R.string.zone_title)
                            Tab.MEMBERS -> stringResource(
                                R.string.members_title,
                                session.members.size,
                            )
                            Tab.SETTINGS -> stringResource(R.string.settings_title)
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.MAP,
                    onClick = { tab = Tab.MAP },
                    icon = { Icon(Icons.Filled.Map, null) },
                    label = { Text(stringResource(R.string.nav_map)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.ZONES,
                    onClick = { tab = Tab.ZONES },
                    icon = { Icon(Icons.Filled.Place, null) },
                    label = { Text(stringResource(R.string.nav_zones)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.MEMBERS,
                    onClick = { tab = Tab.MEMBERS },
                    icon = { Icon(Icons.Filled.Group, null) },
                    label = { Text(stringResource(R.string.nav_members)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.MAP -> MapScreen(
                    session = session,
                    store = store,
                    onRequestLocation = onRequestLocation,
                    onPlaceZone = { lat, lon ->
                        // The tapped coordinates are the zone's coordinates;
                        // nothing is snapped or inferred.
                        zoneDraft = blankZoneNear(session, lat to lon)
                    },
                    onOpenZones = { tab = Tab.ZONES },
                    snackbar = snackbar,
                )

                Tab.ZONES -> ZonesScreen(
                    session = session,
                    store = store,
                    snackbar = snackbar,
                    onCreateZone = { zoneDraft = blankZoneNear(session) },
                )

                Tab.MEMBERS -> MembersScreen(
                    session = session,
                    store = store,
                )

                Tab.SETTINGS -> SettingsScreen(
                    store = store,
                    settings = settings,
                    session = session,
                    onRequestNotifications = onRequestNotifications,
                    snackbar = snackbar,
                )
            }
        }
    }

    // The single zone editor, driven by whichever entry point made the draft.
    zoneDraft?.let { draft ->
        ZoneEditor(
            zone = draft,
            onDismiss = { zoneDraft = null },
            onSave = { updated ->
                scope.launch {
                    store.updateSession { s ->
                        val at = s.zones.indexOfFirst { it.id == updated.id }
                        val zones = if (at >= 0) {
                            s.zones.toMutableList().also { it[at] = updated }
                        } else {
                            s.zones + updated
                        }
                        s.copy(zones = zones)
                    }
                    snackbar.showSnackbar(context.getString(R.string.zone_saved))
                }
                zoneDraft = null
            },
        )
    }
}

private suspend fun ensureIdentity(store: Store): Identity {
    store.identitySeedB64()?.let { b64 ->
        runCatching { Identity.fromSeed(B64.dec(b64)) }.getOrNull()?.let { return it }
    }
    val fresh = Identity.generate()
    store.setIdentitySeed(B64.enc(fresh.seed))
    return fresh
}

// ---------------------------------------------------------------------------
// Onboarding
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(
    settings: Settings,
    inviteDraft: String,
    onInviteChange: (String) -> Unit,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    var joinMode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.onboarding_your_name)) },
            placeholder = { Text(stringResource(R.string.onboarding_your_name_hint)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        if (joinMode) {
            OutlinedTextField(
                value = inviteDraft,
                onValueChange = onInviteChange,
                label = { Text(stringResource(R.string.onboarding_join_paste)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onJoin(inviteDraft) },
                enabled = inviteDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_continue)) }
            TextButton(onClick = { joinMode = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_create)) }
            TextButton(onClick = { joinMode = true }) {
                Text(stringResource(R.string.onboarding_join))
            }
        }
    }
}