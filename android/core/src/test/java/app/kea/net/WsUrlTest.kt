package app.kea.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relay endpoint convention.
 *
 * The group id has to sit in the URL *path* so a Cloudflare Worker can pick
 * the Durable Object before the WebSocket upgrade — it cannot wait for the
 * `hello` frame. But users type whatever they were told, so the client has to
 * absorb every reasonable spelling of the same address.
 *
 * Both backends accept `/ws/<groupId>`:
 *   - the Durable Object relay uses the path to select the object
 *   - relay.py ignores the extra segment and routes by the `hello` group field
 */
class WsUrlTest {

    private val gid = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun appendsGroupToBareHost() {
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("wss://relay.example", gid))
    }

    @Test
    fun toleratesTrailingSlash() {
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("wss://relay.example/", gid))
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("wss://relay.example///", gid))
    }

    @Test
    fun toleratesWsAlreadyPresent() {
        // Someone pasting straight out of the relay's own README.
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("wss://relay.example/ws", gid))
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("wss://relay.example/ws/", gid))
    }

    @Test
    fun keepsNonDefaultPort() {
        assertEquals(
            "ws://172.20.10.4:8080/ws/$gid",
            RelayEndpoint.wsUrl("ws://172.20.10.4:8080", gid),
        )
    }

    @Test
    fun preservesPathPrefix() {
        // A relay mounted under a sub-path by a reverse proxy.
        assertEquals(
            "wss://example.com/kea/ws/$gid",
            RelayEndpoint.wsUrl("wss://example.com/kea", gid),
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("wss://relay.example/ws/$gid", RelayEndpoint.wsUrl("  wss://relay.example  ", gid))
    }
}