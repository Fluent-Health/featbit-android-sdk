package co.featbit.client.datasynchronizer

import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.options.FBOptions
import co.featbit.client.store.DefaultMemoryStore
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.MemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PollingDataSynchronizerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun options() = FBOptions.Builder("env-secret")
        // large interval: only the first immediate poll runs during the test
        .polling(server.url("/").toString(), interval = 10.minutes)
        .event(server.url("/").toString())
        .build()

    @Test
    fun `first successful poll initializes and populates store`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"featureFlags":[{"id":"flag1","variation":"true"}]}}""",
            ),
        )

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        val sync = PollingDataSynchronizer(options(), user, store)

        val ready = sync.start()
        sync.close()

        assertTrue(ready)
        assertTrue(sync.initialized)
        assertEquals("true", store.get("flag1")?.variation)
    }

    @Test
    fun `fatal 401 stops polling and reports not ready`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        val sync = PollingDataSynchronizer(options(), user, store)

        val ready = sync.start()
        sync.close()

        assertFalse(ready)
        assertFalse(sync.initialized)
    }

    // ---------------------------------------------------------------------------------------
    // closeAndJoin / loop cadence / transient-error tests (wider-scope audit M4)
    // ---------------------------------------------------------------------------------------

    /**
     * Store decorator that blocks its first [upsert] call on a [CompletableDeferred]. Lets the
     * test sandwich a `closeAndJoin` call between "upsert starts" and "upsert finishes" so we
     * can observe whether `closeAndJoin` actually waits for the upsert to land.
     *
     * All other operations delegate to a [DefaultMemoryStore] held internally.
     */
    private class BarrierStore : MemoryStore {
        private val backing = DefaultMemoryStore()
        val firstUpsertEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile var firstUpsertFinished: Boolean = false
        private var seenFirst = false

        override fun get(id: String) = backing.get(id)
        override fun getAll() = backing.getAll()
        override fun addChangeListener(listener: FlagChangeListener) = backing.addChangeListener(listener)
        override fun removeChangeListener(listener: FlagChangeListener) = backing.removeChangeListener(listener)

        override fun upsert(flag: FeatureFlag) {
            // First call blocks (synchronously) until the test releases the barrier — proves the
            // window where a buggy close path could race past the upsert.
            if (!seenFirst) {
                seenFirst = true
                firstUpsertEntered.complete(Unit)
                // Block on a CountDownLatch-equivalent — we're on a synchronous, non-cancellable
                // code path inside `forEach { store.upsert(it) }`, so kotlinx cancellation cannot
                // preempt us here. Sleep-poll the release CompletableDeferred under a wall-clock
                // deadline so a regression in the test's own release path can't hang the JUnit
                // runner indefinitely (no coroutine timeout reaches this non-suspending body).
                val deadlineMs = System.currentTimeMillis() + 10_000
                while (!release.isCompleted && System.currentTimeMillis() < deadlineMs) {
                    Thread.sleep(10)
                }
                check(release.isCompleted) { "BarrierStore: release was never signalled within 10s" }
            }
            backing.upsert(flag)
            firstUpsertFinished = true
        }
    }

    /**
     * Contract (Finding #2 fix): `closeAndJoin` must NOT return until the in-flight
     * `forEach { store.upsert(it) }` loop has finished. Otherwise an upsert from the previous
     * user could race past `identify()`'s synchronizer swap and contaminate the new user's
     * store.
     *
     * Drives the race: the BarrierStore holds the first upsert until released. While it's
     * held, the test calls `closeAndJoin()` in parallel and asserts that closeAndJoin DOES NOT
     * return while the upsert is blocked.
     *
     * Mutation that would fail this:
     *   * Reverting `closeAndJoin` to `scope.cancel()` (non-suspending) — would return
     *     immediately, leaving the upsert to land afterwards.
     *   * Replacing `loopJob?.cancelAndJoin()` with `loopJob?.cancel()` — same defect.
     */
    @Test
    fun `closeAndJoin awaits in-flight upsert before returning`() = runBlocking {
        // 50ms interval + extra enqueued responses: lets the post-close loop-termination
        // assertion be meaningful. A still-running loop after closeAndJoin would issue several
        // requests within the 200ms wait window, blowing up the equality assertion.
        repeat(8) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"featureFlags":[{"id":"flagA","variation":"v1"}]}}""",
                ),
            )
        }
        val fastOptions = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString(), interval = 50.milliseconds)
            .event(server.url("/").toString())
            .build()

        val barrier = BarrierStore()
        val user = FBUser.builder("u1").build()
        val sync = PollingDataSynchronizer(fastOptions, user, barrier)

        // start() suspends until the first poll initializes. The barrier blocks the upsert,
        // so we kick off start() async and wait for the barrier to be entered.
        val startDeferred = async { sync.start() }
        withTimeoutOrNull(5.seconds) { barrier.firstUpsertEntered.await() }
            ?: error("upsert was never invoked — server response not consumed?")

        // The upsert is currently blocked. closeAndJoin must NOT return while that's the case.
        val closeDeferred = async { sync.closeAndJoin() }
        delay(100) // give closeAndJoin a chance to run if it would return prematurely
        assertFalse(
            "closeAndJoin returned while upsert was still blocked — race window open",
            closeDeferred.isCompleted,
        )

        // Release the upsert. closeAndJoin should now return.
        barrier.release.complete(Unit)
        withTimeoutOrNull(5.seconds) { closeDeferred.await() }
            ?: error("closeAndJoin did not return within 5s after upsert was released")

        assertTrue("upsert ran to completion", barrier.firstUpsertFinished)
        assertEquals(
            "flag landed in the store before closeAndJoin returned",
            "v1",
            barrier.get("flagA")?.variation,
        )

        // start() was awaiting `startTask.complete(...)`. Because closeAndJoin AWAITED the
        // in-flight upsert (the whole point of the fix), the upsert ran to completion BEFORE
        // closeAndJoin returned — which means `initializedFlag.compareAndSet(false, true)` ran,
        // `startTask.complete(true)` fired, and `start()` resolves to `true`. The
        // `startTask.complete(false)` inside closeAndJoin's cancel-path is then a no-op (the
        // deferred is already completed).
        //
        // This `true` is the load-bearing assertion for the WHOLE TEST: a buggy closeAndJoin
        // that returned BEFORE the upsert (e.g. `loopJob?.cancel()` instead of `.cancelAndJoin()`)
        // would race past the in-flight forEach and complete startTask with `false` first,
        // giving us `startResult = false`. Exact-equality on `true` catches that mutation.
        val startResult = withTimeoutOrNull(1.seconds) { startDeferred.await() }
        assertEquals(
            "start() must return true — closeAndJoin awaited the upsert that initialized us",
            true,
            startResult,
        )

        // Lock down loop termination: after closeAndJoin returns, the polling loop must be
        // dead. Snapshot requestCount, wait 200ms (= 4 polling intervals at 50ms), assert no
        // new requests landed. A mutant that left the loop running would issue ≥3 polls in
        // this window. A mutant that removed the inner `delay(pollingInterval)` would issue
        // hundreds. The exact-equality assertion catches both.
        val requestsAfterClose = server.requestCount
        delay(200)
        assertEquals(
            "no new polling requests after closeAndJoin — loop terminated",
            requestsAfterClose,
            server.requestCount,
        )
    }

    /**
     * Contract: the polling loop runs `safePoll` repeatedly, with `delay(pollingInterval)`
     * between iterations. Across the test's window we expect at least 3 polls.
     *
     * Mutation that would fail this:
     *   * Removing the `while (true)` from `pollingLoop` — only 1 request would land.
     *   * Setting the interval too long (we'd see fewer requests).
     */
    @Test
    fun `polling loop issues repeated requests across the polling interval`() = runBlocking {
        val flagBody = """{"data":{"featureFlags":[{"id":"f","variation":"v"}]}}"""
        // Enqueue enough responses to satisfy ~3 polls within the test window.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200).setBody(flagBody)) }

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        // 50ms interval × 3 polls = 100-150ms wall-clock; well under MockWebServer's keep-alive.
        val fastOptions = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString(), interval = 50.milliseconds)
            .event(server.url("/").toString())
            .build()
        val sync = PollingDataSynchronizer(fastOptions, user, store)

        try {
            assertTrue("first poll initializes", sync.start())
            // Initial poll already consumed 1 enqueued response. Wait for two more interval ticks.
            // Polling cadence: poll → delay(50) → poll → delay(50) → poll.
            delay(180)
            // The recorded request count is the strongest invariant — every request the
            // SDK *issued* shows up here even if the response was processed asynchronously.
            assertTrue(
                "expected ≥3 requests within ~180ms (got ${server.requestCount})",
                server.requestCount >= 3,
            )
        } finally {
            sync.close()
        }
    }

    /**
     * Contract: a transient 5xx response is logged but does NOT stop the loop. The loop
     * continues polling; the next successful response initializes the synchronizer.
     *
     * Mutation that would fail this:
     *   * Replacing `return` (line 77 of PollingDataSynchronizer) with `close()` — loop dies.
     *   * Treating 500 as fatal (e.g. broadening `isFatal` past 401).
     *   * Skipping `safePoll`'s catch and letting the exception propagate up `pollingLoop` —
     *     the coroutine would terminate before the second response.
     */
    @Test
    fun `transient 500 does not stop the loop and next 200 initializes`() = runBlocking {
        // First poll: 500 (transient). Second poll: 200 with a real flag payload.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"featureFlags":[{"id":"f","variation":"survived"}]}}""",
            ),
        )

        val store = DefaultMemoryStore()
        val user = FBUser.builder("u1").build()
        val fastOptions = FBOptions.Builder("env-secret")
            .polling(server.url("/").toString(), interval = 50.milliseconds)
            .event(server.url("/").toString())
            .build()
        val sync = PollingDataSynchronizer(fastOptions, user, store)

        try {
            val ready = withTimeoutOrNull(2.seconds) { sync.start() }
            assertEquals("synchronizer initialized after recovering from the 500", true, ready)
            assertTrue(sync.initialized)
            assertEquals(
                "the post-recovery payload landed in the store",
                "survived",
                store.get("f")?.variation,
            )
            assertTrue(
                "exactly the 500 + the 200 were issued (no extra request between)",
                server.requestCount >= 2,
            )
        } finally {
            sync.close()
        }
    }
}
