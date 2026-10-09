package app.kea

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.kea.crypto.B64
import app.kea.crypto.Identity
import app.kea.crypto.InviteLink
import app.kea.data.Store
import app.kea.service.ShareService
import app.kea.ui.KeaRoot
import app.kea.ui.KeaTheme
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store

    /** Only asks for foreground location; background is requested separately. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            lifecycleScope.launch {
                val granted = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (granted) ShareService.start(this@MainActivity)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MapLibre must be initialised before any MapView is created.
        MapLibre.getInstance(this)

        store = Store(applicationContext)
        requestPermissionsIfNeeded()

        setContent {
            KeaTheme {
                val session by store.session.collectAsStateWithLifecycle(initialValue = null)
                val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)

                KeaRoot(
                    store = store,
                    session = session,
                    settings = settings,
                    onRequestLocation = { requestPermissionsIfNeeded() },
                    onRequestNotifications = {
                        runCatching {
                            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Accept a `kea://join#…` invite.
     *
     * The fragment carries the group key, so it is read locally and never sent
     * anywhere — including to our own relay. That is the entire point of
     * putting it in a fragment.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "kea" || data.host != "join") return

        val link = "kea://join#${data.fragment}"
        lifecycleScope.launch {
            val invite = runCatching { InviteLink.parse(link) }.getOrNull() ?: return@launch
            val identity = ensureIdentity()
            store.updateSession { s ->
                s.copy(
                    group = app.kea.data.Group(
                        groupId = B64.enc(invite.groupId),
                        groupKey = B64.enc(invite.groupKey),
                        name = data.getQueryParameter("name") ?: "",
                        createdAt = System.currentTimeMillis(),
                        inviteLink = link,
                    ),
                    members = listOf(
                        app.kea.data.Member(
                            deviceId = identity.deviceId,
                            name = "",
                            publicKey = B64.enc(identity.publicKey),
                            isSelf = true,
                        ),
                    ),
                )
            }
            ShareService.start(this@MainActivity)
        }
    }

    private suspend fun ensureIdentity(): Identity {
        store.identitySeedB64()?.let { b64 ->
            runCatching { Identity.fromSeed(B64.dec(b64)) }.getOrNull()?.let { return it }
        }
        val fresh = Identity.generate()
        store.setIdentitySeed(B64.enc(fresh.seed))
        return fresh
    }

    private var askedThisSession = false

    private fun requestPermissionsIfNeeded() {
        if (askedThisSession) return
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            ShareService.start(this)
            return
        }
        askedThisSession = true
        permissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        // The map is about to be drawn, so we are a watcher. This is the flag
        // that switches every other member's phone into streaming mode.
        ShareService.setWatching(this, true)
    }

    override fun onStop() {
        ShareService.setWatching(this, false)
        super.onStop()
    }
}