package app.kea.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import app.kea.net.ControlJson

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kea")

/**
 * The single persistence gateway.
 *
 * Everything is a StateFlow derived from DataStore, so the UI, the service and
 * the socket all observe the same source of truth and cannot drift. Writes are
 * serialised by DataStore, which removes a whole class of lost-update races
 * between "a member arrived" and "a zone was edited".
 */
class Store(private val context: Context) {

    private object Keys {
        val SESSION = stringPreferencesKey("session_json")
        val SETTINGS = stringPreferencesKey("settings_json")
        val IDENTITY_SEED = stringPreferencesKey("identity_seed_b64")
        val ACTIVE_GROUP = stringPreferencesKey("active_group_b64")
    }

    // A deliberately lenient codec: a field added in a later build must not make
    // an older install unreadable, and an unreadable store must not crash the app.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val session: Flow<Session> = context.dataStore.data.map { prefs ->
        prefs[Keys.SESSION]?.let { decode<Session>(it) } ?: Session()
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        prefs[Keys.SETTINGS]?.let { decode<Settings>(it) } ?: Settings()
    }

    private inline fun <reified T> decode(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        null
    }

    suspend fun updateSession(block: (Session) -> Session) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SESSION]?.let { decode<Session>(it) } ?: Session()
            prefs[Keys.SESSION] = json.encodeToString(Session.serializer(), block(current))
        }
    }

    suspend fun updateSettings(block: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SETTINGS]?.let { decode<Settings>(it) } ?: Settings()
            prefs[Keys.SETTINGS] = json.encodeToString(Settings.serializer(), block(current))
        }
    }

    // ---- identity --------------------------------------------------------

    /**
     * The Ed25519 seed, base64url.
     *
     * Stored in the app's private DataStore rather than the platform keystore
     * because we need the raw seed bytes, not an opaque key handle, to derive
     * the public key and sign on a background thread. The file is inside the
     * app sandbox and excluded from backup (see data_extraction_rules.xml).
     */
    suspend fun identitySeedB64(): String? =
        context.dataStore.data.first()[Keys.IDENTITY_SEED]

    suspend fun setIdentitySeed(b64: String) {
        context.dataStore.edit { it[Keys.IDENTITY_SEED] = b64 }
    }

    // ---- lifecycle -------------------------------------------------------

    suspend fun clearGroup() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SESSION)
            prefs.remove(Keys.ACTIVE_GROUP)
        }
    }
}