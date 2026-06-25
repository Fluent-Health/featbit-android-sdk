package co.featbit.client

import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FBClientImplTest {

    private val user = FBUser.builder("u1").name("bob").build()

    private fun offlineClientWith(vararg flags: FeatureFlag): FBClient {
        val options = FBOptions.Builder()
            .offline(true)
            .bootstrap(flags.toList())
            .build()
        return FBClientImpl(options, user)
    }

    @Test
    fun `offline client with bootstrap is initialized and evaluates flags`() = runBlocking {
        val client = offlineClientWith(
            FeatureFlag(id = "bool-flag", variation = "true", matchReason = "fallthrough"),
            FeatureFlag(id = "str-flag", variation = "hello"),
        )

        assertTrue(client.start())
        assertTrue(client.initialized)

        assertTrue(client.boolVariation("bool-flag"))
        assertEquals("hello", client.stringVariation("str-flag"))

        val detail = client.boolVariationDetail("bool-flag")
        assertTrue(detail.value)
        assertEquals("fallthrough", detail.reason)

        client.close()
    }

    @Test
    fun `unknown flag returns default with flag-not-found reason`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "known", variation = "true"))
        client.start()

        assertFalse(client.boolVariation("unknown", default = false))
        assertEquals("flag not found", client.boolVariationDetail("unknown").reason)

        client.close()
    }

    @Test
    fun `type mismatch returns default`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "weird", variation = "not-a-bool"))
        client.start()

        assertFalse(client.boolVariation("weird", default = false))
        assertEquals("type mismatch", client.boolVariationDetail("weird").reason)

        client.close()
    }

    @Test
    fun `not-ready client without bootstrap returns client-not-ready`() {
        // Non-offline, never started: data synchronizer reports not initialized.
        val options = FBOptions.Builder("secret").build()
        val client = FBClientImpl(options, user)

        assertFalse(client.initialized)
        val detail = client.stringVariationDetail("any", default = "fallback")
        assertEquals("client not ready", detail.reason)
        assertEquals("fallback", detail.value)

        client.close()
    }

    /**
     * Contract: the fast-path `*Variation()` getters honor the same not-ready guard as
     * `evaluateCore`. With an uninitialized client and no bootstrap, every fast-path getter
     * must return the caller's default — no flag lookup, no insight emission, no exception.
     *
     * This pins the `if (!initialized && options.bootstrap.isEmpty()) return default` line
     * in `evaluateValue`. The combined "value and detail getters agree" test runs against an
     * offline+bootstrap client (initialized=true), so it can't catch a mutation that drops
     * this guard — this test does.
     *
     * Mutation that would fail this:
     *   * Removing the not-ready guard in evaluateValue — the call would fall through to
     *     `evaluator.evaluate(key)`, which would still return NotFound for an empty store
     *     (so default would be returned by accident). BUT: insight would also be skipped on
     *     NotFound, so the *observable* output stays "default" either way. The mutation is
     *     therefore semantically benign for the empty-store case. Acknowledge: this test
     *     can't catch the mutation as currently written without store inspection. What it
     *     DOES catch is a stronger mutation: the fast-path throwing or returning a
     *     non-default value when called pre-init.
     *   * Fast-path crashing on null userRef before guard checked — would throw rather
     *     than return default cleanly.
     *
     * Honest scope: the in-tree guard is mechanical and read by code review. This test
     * pins the end-to-end "no throw, returns default" surface.
     */
    @Test
    fun `fast-path returns default when uninitialized without bootstrap`() {
        // Non-offline, never started, no bootstrap — explicit pre-init state.
        val options = FBOptions.Builder("secret").build()
        val client = FBClientImpl(options, user)
        try {
            assertFalse("client must be in pre-init state", client.initialized)

            // Every fast-path getter must return the caller's default.
            assertEquals(false, client.boolVariation("any", default = false))
            assertEquals(true, client.boolVariation("any", default = true))
            assertEquals(-1, client.intVariation("any", default = -1))
            assertEquals(0.5f, client.floatVariation("any", default = 0.5f))
            assertEquals(0.25, client.doubleVariation("any", default = 0.25), 0.0001)
            assertEquals("fallback", client.stringVariation("any", default = "fallback"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `allFlags returns bootstrap snapshot`() {
        val client = offlineClientWith(
            FeatureFlag(id = "a", variation = "1"),
            FeatureFlag(id = "b", variation = "2"),
        )
        val all = client.allFlags()
        assertEquals(setOf("a", "b"), all.keys)
        assertEquals("1", all["a"]?.variation)
        client.close()
    }

    /**
     * Contract: `*Variation()` (no-detail) and `*VariationDetail()` are sibling APIs that
     * must agree on the returned value for every input. The non-detail variants go through
     * the `evaluateValue` fast-path which skips the `EvalDetail` allocation; detail variants
     * go through `evaluateCore`. Both code paths must produce the same value across:
     *   - found flag with valid conversion
     *   - found flag with type mismatch (falls back to default)
     *   - unknown flag (falls back to default)
     *   - bool / int / float / double / string converter coverage
     *
     * Mutation that would fail this:
     *   * The fast-path uses a different converter than the detail-path (e.g. swaps int for
     *     float) → typed value diverges between the two getters.
     *   * Fast-path skips the "type mismatch → default" branch (returns the converter's
     *     null instead of falling back) → mismatched-type cases would NPE or return wrong
     *     value.
     *
     * Note: this test runs with `initialized=true` (offline client with bootstrap), so it
     * does NOT cover the fast-path's `!initialized && bootstrap.isEmpty()` guard. That
     * branch is exercised separately by `fast-path returns default when uninitialized
     * without bootstrap`.
     */
    @Test
    fun `value and detail getters agree across types and miss-paths`() = runBlocking {
        val client = offlineClientWith(
            FeatureFlag(id = "bool-flag", variation = "true"),
            FeatureFlag(id = "int-flag", variation = "42"),
            FeatureFlag(id = "float-flag", variation = "1.5"),
            FeatureFlag(id = "double-flag", variation = "2.25"),
            FeatureFlag(id = "string-flag", variation = "hello"),
            FeatureFlag(id = "bad-bool", variation = "not-a-bool"),
            FeatureFlag(id = "bad-int", variation = "not-an-int"),
        )
        client.start()
        try {
            assertEquals(true, client.boolVariation("bool-flag"))
            assertEquals(client.boolVariationDetail("bool-flag").value, client.boolVariation("bool-flag"))

            assertEquals(42, client.intVariation("int-flag", default = 0))
            assertEquals(client.intVariationDetail("int-flag", default = 0).value, client.intVariation("int-flag", default = 0))

            assertEquals(1.5f, client.floatVariation("float-flag", default = 0f))
            assertEquals(client.floatVariationDetail("float-flag", default = 0f).value, client.floatVariation("float-flag", default = 0f))

            assertEquals(2.25, client.doubleVariation("double-flag", default = 0.0), 0.0001)
            assertEquals(client.doubleVariationDetail("double-flag", default = 0.0).value, client.doubleVariation("double-flag", default = 0.0), 0.0001)

            assertEquals("hello", client.stringVariation("string-flag"))
            assertEquals(client.stringVariationDetail("string-flag").value, client.stringVariation("string-flag"))

            // Type mismatch — both must fall back to default.
            assertEquals(false, client.boolVariation("bad-bool", default = false))
            assertEquals(client.boolVariationDetail("bad-bool", default = false).value, client.boolVariation("bad-bool", default = false))

            assertEquals(-1, client.intVariation("bad-int", default = -1))
            assertEquals(client.intVariationDetail("bad-int", default = -1).value, client.intVariation("bad-int", default = -1))

            // Unknown flag — both must fall back to default.
            assertEquals("fallback", client.stringVariation("missing", default = "fallback"))
            assertEquals(client.stringVariationDetail("missing", default = "fallback").value, client.stringVariation("missing", default = "fallback"))
        } finally {
            client.close()
        }
    }

    // ---------------------------------------------------------------------------------------
    // identify / close contract tests (wider-scope audit H5)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: `identify(user)` must swap the synchronizer and re-fetch flags. After identify
     * resolves, evaluation reflects the NEW user's payload, not the initial user's.
     *
     * Drives `FBClientImpl` in polling mode against `MockWebServer` — proves the swap end-to-end
     * (closeAndJoin of previous sync, fresh sync starts, server is asked for new user's flags,
     * store is updated, evaluation sees the update).
     *
     * Mutation that would fail this:
     *   * `identify` not setting `syncRef = fresh`.
     *   * `identify` re-using the old synchronizer (would never re-fetch).
     *   * `closeAndJoin` clearing the store mid-swap.
     *   * `identify` returning before the new sync's first response landed.
     */
    @Test
    fun `identify swaps synchronizer and evaluation reflects new user payload`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // First user gets "alpha"; second user gets "beta". Polling interval is huge so the
            // *only* time the SDK hits the server for each user is its initial fetch.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"featureFlags":[{"id":"f","variation":"alpha","matchReason":"fallthrough"}]}}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"featureFlags":[{"id":"f","variation":"beta","matchReason":"fallthrough"}]}}""",
                ),
            )

            val options = FBOptions.Builder("secret")
                .polling(server.url("/").toString(), interval = 10.seconds)
                .event(server.url("/").toString())
                .build()
            val client = FBClientImpl(options, FBUser.builder("user-A").build())

            try {
                assertTrue("initial start should succeed", client.start(timeout = 5.seconds))
                assertEquals(
                    "user-A evaluation must reflect server's 'alpha' payload",
                    "alpha",
                    client.stringVariation("f", default = "fallback"),
                )

                assertTrue(
                    "identify should succeed within timeout",
                    client.identify(FBUser.builder("user-B").build(), timeout = 5.seconds),
                )

                assertEquals(
                    "after identify, evaluation must reflect user-B's 'beta' payload",
                    "beta",
                    client.stringVariation("f", default = "fallback"),
                )
            } finally {
                client.close()
            }
        } finally {
            server.shutdown()
        }
    }

    /**
     * Contract: `close()` is idempotent. The public `Closeable.close` contract permits multiple
     * calls; the SDK must not crash, hang, or throw on a second close. This pins the runBlocking
     * + per-phase withTimeoutOrNull pattern's stability under double-invocation.
     *
     * For an offline client (NoopTrackInsight + NullDataSynchronizer + already-cancelled scope
     * after the first close), the second close should be microsecond-scale. A 50ms threshold
     * is generous enough to absorb GC pause / CI noise but tight enough to catch any mutation
     * where the second close suspends on a phantom timeout or stuck channel.close().
     *
     * Mutation that would fail this:
     *   * Removing the `withTimeoutOrNull` wrappers and letting a cancelled scope's
     *     `closeAndJoin`/`closeAndDrain` throw on the second call.
     *   * Any second-close path that suspends for >50ms (e.g. waiting on a timeout that
     *     never resolves because the scope is already cancelled).
     */
    @Test
    fun `close is idempotent`() {
        val client = offlineClientWith(FeatureFlag(id = "f", variation = "v"))
        client.close()
        val startNs = System.nanoTime()
        client.close()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue(
            "second close must return promptly (got ${elapsedMs}ms, expected < 50ms)",
            elapsedMs < 50,
        )
    }

    /**
     * Contract: `close()` on an offline client (no network, no in-flight sync) returns promptly.
     * Pins the lower bound of the close budget — proves the runBlocking{} bridge isn't
     * waiting on a timeout that never fires when there's nothing to wait for.
     *
     * Mutation that would fail this:
     *   * `close()` blocking on a sleep, indefinite wait, or wrong condition.
     *   * `runBlocking { ... }` body waiting on a never-resolving deferred.
     */
    @Test
    fun `close completes promptly when offline`() {
        val client = offlineClientWith(FeatureFlag(id = "f", variation = "v"))
        val startNs = System.nanoTime()
        client.close()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue(
            "offline close must return well under the per-phase 2s budgets (got ${elapsedMs}ms)",
            elapsedMs < 500,
        )
    }

    /**
     * Contract: when the sync teardown can't make progress (e.g. polling sync is blocked in
     * a non-responsive socket read), `close()` is bounded by `SYNC_CLOSE_TIMEOUT_MS = 2_000ms`
     * — the per-phase budget set in `FBClientImpl.close()`. Without that budget, `close` would
     * hang until OkHttp's 8s readTimeout fires (`FbApiClient.READ_TIMEOUT_SECONDS`).
     *
     * Setup: MockWebServer accepts the socket but never enqueues a response. PollingSync's
     * first poll blocks in OkHttp's synchronous `socket.read`. `cancelAndJoin` can't preempt
     * that read until readTimeout — so the outer `withTimeoutOrNull(2_000ms)` is the only
     * thing keeping `close()` bounded.
     *
     * Mutation that would fail this:
     *   * Removing or extending `SYNC_CLOSE_TIMEOUT_MS` above ~3s.
     *   * Removing the outer `withTimeoutOrNull(SYNC_CLOSE_TIMEOUT_MS) { ... }` wrapper —
     *     close would hang until OkHttp's 8s readTimeout.
     */
    @Test
    fun `close is bounded when sync teardown is blocked on a non-responsive server`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // No enqueued responses — the polling sync's first request will block in
            // OkHttp's read until either cancellation cuts through or the 8s readTimeout fires.
            val options = FBOptions.Builder("secret")
                .polling(server.url("/").toString(), interval = 10.seconds)
                .event(server.url("/").toString())
                .build()
            val client = FBClientImpl(options, FBUser.builder("u1").build())

            // Kick off start() with a short timeout so the polling sync issues its first request
            // (which blocks in OkHttp's read since the server has no enqueued responses), then
            // measure close(). start() itself returns false on the 200ms timeout; that's fine —
            // we just need the polling loop to have begun a real HTTP call before we close.
            client.start(timeout = 200.milliseconds)

            val startNs = System.nanoTime()
            client.close()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            // 2_500ms = SYNC_CLOSE_TIMEOUT_MS (2_000) + ~500ms CI slack (measured ~2.2s on
            // local). A mutation that extended the constant past ~2.5s would fail here; a
            // mutation that removed the outer withTimeoutOrNull would hit OkHttp's 8s
            // readTimeout. The tight bound is intentional — looser thresholds let a 50%+
            // drift in the constant pass silently.
            assertTrue(
                "close must be bounded by SYNC_CLOSE_TIMEOUT_MS=2s + slack, not by OkHttp's " +
                    "8s readTimeout (got ${elapsedMs}ms)",
                elapsedMs < 2_500,
            )
        } finally {
            server.shutdown()
        }
    }

    /**
     * Contract: after `close()`, `initialized` continues to reflect the last-known state and
     * evaluations return defaults / fall back via `client not ready` if the synchronizer was
     * never initialized. The store contents survive — close does NOT wipe data.
     *
     * Mutation that would fail this:
     *   * `close()` clearing the store.
     *   * `close()` flipping `initialized` back to false in a way that breaks the bootstrap path.
     */
    @Test
    fun `close preserves store and bootstrap evaluations still work`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "f", variation = "true"))
        assertTrue(client.start())
        assertTrue("bootstrap flag evaluates before close", client.boolVariation("f"))

        client.close()

        // The store survived; bootstrap-backed evaluation still resolves. Note this is the
        // contract for the offline+bootstrap path — a non-bootstrap client may behave
        // differently post-close depending on whether the network sync ever completed.
        assertTrue("bootstrap flag still evaluates after close", client.boolVariation("f"))
    }

    /**
     * Contract: `identify` on an offline client with bootstrap returns `true` (NullDataSync's
     * `start()` is vacuously successful). User swap is recorded internally even when no
     * network call happens.
     *
     * Mutation that would fail this:
     *   * `identify` swapping in a synchronizer that reports `initialized=false` for offline.
     *   * `NullDataSynchronizer.start()` returning `false`.
     */
    @Test
    fun `offline identify returns true without network`() = runBlocking {
        val client = offlineClientWith(FeatureFlag(id = "f", variation = "true"))
        assertTrue(client.start())
        assertTrue(
            "offline identify must succeed (NullDataSynchronizer.start = true)",
            client.identify(FBUser.builder("u2").build(), timeout = 1.seconds),
        )
        assertTrue("client remains initialized after offline identify", client.initialized)
        client.close()
    }

    /**
     * Contract: `start(timeout)` must return `false` (not throw) when the synchronizer fails to
     * initialize within the budget. The current code uses `withTimeoutOrNull` which returns
     * `null` -> coerced to `false`. This pins that behavior — a regression that threw or hung
     * here would break user-facing start() semantics.
     *
     * Mutation that would fail this:
     *   * Replacing `withTimeoutOrNull` with `withTimeout` (would throw).
     *   * `withTimeoutOrNull` body suppressing the timeout (would hang).
     */
    @Test
    fun `start returns false when polling sync cannot initialize within timeout`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // Never enqueue a response — the polling call will hang until the test's start()
            // timeout, at which point start() should return false rather than throw.
            val options = FBOptions.Builder("secret")
                .polling(server.url("/").toString(), interval = 10.seconds)
                .event(server.url("/").toString())
                .build()
            val client = FBClientImpl(options, FBUser.builder("u1").build())
            try {
                val startNs = System.nanoTime()
                val started = client.start(timeout = 200.milliseconds)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

                assertFalse("start must return false on timeout, not throw", started)
                assertFalse(
                    "initialized must remain false after failed start — catches mutations that " +
                        "flip the flag on timeout",
                    client.initialized,
                )
                assertTrue(
                    "start respects the timeout (got ${elapsedMs}ms, expected ~200ms + slack)",
                    elapsedMs in 100..2_000,
                )
            } finally {
                client.close()
            }
        } finally {
            server.shutdown()
        }
    }
}
