package app.kea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kea.KeaApp
import app.kea.R
import app.kea.crypto.B64
import app.kea.crypto.InviteLink
import app.kea.data.Group
import app.kea.data.Member
import app.kea.data.Palette
import app.kea.data.colour
import app.kea.data.toColourInt
import app.kea.data.Session
import app.kea.data.Settings
import app.kea.data.Store
import app.kea.data.Zone
import app.kea.service.ShareService
import kotlinx.coroutines.launch

// ===========================================================================
// Zones
// ===========================================================================

@Composable
fun ZonesScreen(
    session: Session,
    store: Store,
    snackbar: SnackbarHostState,
    onCreateZone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Zone?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.zone_tap_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(session.zones, key = { it.id }) { zone ->
                ZoneRow(
                    zone = zone,
                    membersInside = session.members.count { member ->
                        val lat = member.lat ?: return@count false
                        val lon = member.lon ?: return@count false
                        if (zone.isPolygon) {
                            app.kea.geo.Geo.inPolygon(lat, lon, zone.ringPairs())
                        } else {
                            app.kea.geo.Geo.distanceMeters(lat, lon, zone.lat, zone.lon) <= zone.radiusM
                        }
                    },
                    onClick = { editing = zone },
                    onDelete = {
                        scope.launch {
                            store.updateSession { s ->
                                s.copy(zones = s.zones.filterNot { it.id == zone.id })
                            }
                            snackbar.showSnackbar(context.getString(R.string.zone_deleted))
                        }
                    },
                )
                HorizontalDivider()
            }
            if (session.zones.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.zone_add),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                }
            }
        }

FloatingActionButton(
            onClick = onCreateZone,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Filled.Add, stringResource(R.string.zone_add)) }
    }

    editing?.let { zone ->
        ZoneEditor(
            zone = zone,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    store.updateSession { s ->
                        val existing = s.zones.indexOfFirst { it.id == updated.id }
                        val zones = if (existing >= 0) {
                            s.zones.toMutableList().also { it[existing] = updated }
                        } else {
                            s.zones + updated
                        }
                        s.copy(zones = zones)
                    }
                    snackbar.showSnackbar(context.getString(R.string.zone_saved))
                }
                editing = null
            },
        )
    }
}

/**
 * A blank zone at the user's last known position.
 *
 * Defaulting to (0, 0) would silently drop the zone in the Gulf of Guinea,
 * which is why placement from the map is the primary route and this is only the
 * fallback for people who came here from the Zones tab.
 */
fun blankZoneNear(session: Session, at: Pair<Double, Double>? = null): Zone {
    val self = session.members.firstOrNull { it.isSelf }
    val lat = at?.first ?: self?.lat ?: DEFAULT_LAT
    val lon = at?.second ?: self?.lon ?: DEFAULT_LON
    return Zone(
        id = java.util.UUID.randomUUID().toString().take(8),
        name = "",
        lat = lat,
        lon = lon,
        colorIndex = session.zones.size,
    )
}

private const val DEFAULT_LAT = 52.520008
private const val DEFAULT_LON = 13.404954

@Composable
private fun ZoneRow(zone: Zone, membersInside: Int, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(Color(Palette.colour(zone.colorIndex).toColourInt()), CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                zone.name.ifBlank { stringResource(R.string.zone_new) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildString {
                    append(
                        if (zone.isPolygon) stringResource(R.string.zone_shape_polygon)
                        else context.getString(R.string.zone_radius_value, zone.radiusM),
                    )
                    if (membersInside > 0) {
                        append(" · ")
                        append(context.getString(R.string.zone_inside_count, membersInside))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
        }
    }
}

@Composable
internal fun ZoneEditor(zone: Zone, onDismiss: () -> Unit, onSave: (Zone) -> Unit) {
    var name by rememberSaveable(zone.id) { mutableStateOf(zone.name) }
    var radius by rememberSaveable(zone.id) { mutableStateOf(zone.radiusM.toFloat()) }
    var notifyEnter by rememberSaveable(zone.id) { mutableStateOf(zone.notifyEnter) }
    var notifyExit by rememberSaveable(zone.id) { mutableStateOf(zone.notifyExit) }
    var notifySelf by rememberSaveable(zone.id) { mutableStateOf(zone.notifySelf) }
    var notifyOthers by rememberSaveable(zone.id) { mutableStateOf(zone.notifyOthers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.zone_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.zone_name)) },
                    placeholder = { Text(stringResource(R.string.zone_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text(stringResource(R.string.zone_radius_value, radius.toInt()))
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 25f..2000f,
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.zone_notify), style = MaterialTheme.typography.labelLarge)
                SwitchRow(stringResource(R.string.zone_notify_enter), notifyEnter) { notifyEnter = it }
                SwitchRow(stringResource(R.string.zone_notify_exit), notifyExit) { notifyExit = it }
                SwitchRow(stringResource(R.string.zone_notify_self), notifySelf) { notifySelf = it }
                SwitchRow(stringResource(R.string.zone_notify_others), notifyOthers) { notifyOthers = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        zone.copy(
                            name = name.trim(),
                            radiusM = radius.toInt(),
                            notifyEnter = notifyEnter,
                            notifyExit = notifyExit,
                            notifySelf = notifySelf,
                            notifyOthers = notifyOthers,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ===========================================================================
// Members
// ===========================================================================

@Composable
fun MembersScreen(session: Session, store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmLeave by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(session.members, key = { it.deviceId }) { member ->
                MemberRowDetailed(member)
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        OutlinedButton(
            onClick = { confirmLeave = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) { Text(stringResource(R.string.member_leave)) }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.member_leave_confirm_title)) },
            text = { Text(stringResource(R.string.member_leave_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ShareService.stop(context)
                        store.clearGroup()
                    }
                    confirmLeave = false
                }) { Text(stringResource(R.string.member_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemberRowDetailed(member: Member) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(Color(member.colour().toColourInt()), CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(member.name.ifBlank { member.deviceId })
                    if (member.isSelf) append(" · ").append(stringResource(R.string.member_you))
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                member.deviceId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append(
                        if (member.online) context.getString(R.string.member_online)
                        else context.getString(R.string.member_offline),
                    )
                    append(" · ")
                    append(
                        if (member.watching) context.getString(R.string.member_watching)
                        else context.getString(R.string.member_not_watching),
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===========================================================================
// Settings
// ===========================================================================

@Composable
fun SettingsScreen(
    store: Store,
    settings: Settings,
    session: Session,
    onRequestNotifications: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- invite -------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.invite_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    session.group?.inviteLink.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val link = session.group?.inviteLink.orEmpty()
                        val send = android.content.Intent(
                            android.content.Intent.ACTION_SEND,
                        ).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(send, context.getString(R.string.invite_share)),
                        )
                    }) { Text(stringResource(R.string.invite_share)) }

                    OutlinedButton(onClick = {
                        val link = session.group?.inviteLink.orEmpty()
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(android.content.ClipData.newPlainText("KEA", link))
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.invite_copied))
                        }
                    }) { Text(stringResource(R.string.action_copy)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.invite_warning_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- name ---------------------------------------------------------
        var name by rememberSaveable { mutableStateOf(settings.displayName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- language -----------------------------------------------------
        // The user's most likely reason to open settings at all; given its own
        // section rather than being buried.
        var languageMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    buildString {
                        append(stringResource(R.string.settings_language))
                        append(": ")
                        append(
                            when (settings.languageTag) {
                                "en" -> context.getString(R.string.settings_language_en)
                                "de" -> context.getString(R.string.settings_language_de)
                                else -> context.getString(R.string.settings_language_system)
                            },
                        )
                    },
                )
            }
            DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                listOf(
                    "" to context.getString(R.string.settings_language_system),
                    "en" to context.getString(R.string.settings_language_en),
                    "de" to context.getString(R.string.settings_language_de),
                ).forEach { (tag, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            languageMenu = false
                            KeaApp.persistLanguage(context, tag)
                            scope.launch { store.updateSettings { it.copy(languageTag = tag) } }
                        },
                    )
                }
            }
        }

        // ---- relay --------------------------------------------------------
        var relay by rememberSaveable { mutableStateOf(settings.relayUrl) }
        OutlinedTextField(
            value = relay,
            onValueChange = { relay = it },
            label = { Text(stringResource(R.string.settings_relay)) },
            placeholder = { Text(stringResource(R.string.settings_relay_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    store.updateSettings { it.copy(relayUrl = relay.trim()) }
                    // Reconnect immediately rather than waiting for a retry tick.
                    ShareService.setWatching(context, false)
                    ShareService.setWatching(context, true)
                    snackbar.showSnackbar(context.getString(R.string.settings_relay_saved))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_save)) }

        Text(
            stringResource(R.string.settings_relay_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- map data -----------------------------------------------------
        Text(
            stringResource(R.string.settings_map_tiles_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        TextButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.perm_notifications_title))
        }

        TextButton(
            onClick = { ShareService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sharing_idle)) }
    }
}