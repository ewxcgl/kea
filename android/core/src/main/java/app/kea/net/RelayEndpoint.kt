package app.kea.net

/**
 * Where the relay endpoint lives, and how one is built.
 *
 * The group id has to sit in the URL **path**, not only in the `hello` frame.
 * Cloudflare Durable Objects are selected before the WebSocket upgrade, so an
 * edge that routes by group cannot wait for a message to learn where to send
 * the socket. Both backends therefore accept `/ws/<groupId>`:
 *
 *  - the Durable Object relay uses the path to pick the object
 *  - relay.py ignores the extra segment and routes by the `hello` group field
 *
 * That means one URL works against either backend, which is the point: nobody
 * should have to know the convention, and a URL pasted from either project's
 * documentation just works.
 */
object RelayEndpoint {

    const val PATH_PREFIX = "/ws"

    /**
     * Normalise whatever was typed in Settings into a full endpoint.
     *
     * Accepts `wss://host`, `wss://host/`, or `wss://host/ws` and produces
     * `wss://host/ws/<groupId>`. Any path prefix is preserved, because a relay
     * may sit behind a reverse proxy on a sub-path.
     */
    fun wsUrl(configured: String, groupB64: String): String {
        var base = configured.trim()
        while (base.endsWith("/")) base = base.dropLast(1)
        if (base.endsWith(PATH_PREFIX)) base = base.dropLast(PATH_PREFIX.length)
        return "$base$PATH_PREFIX/$groupB64"
    }
}