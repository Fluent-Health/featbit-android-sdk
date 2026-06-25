package co.featbit.client

import co.featbit.client.datasynchronizer.DataSynchronizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleControllerTest {

    private val grace = 1_000L

    private class FakeSynchronizer : DataSynchronizer {
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        override val initialized: Boolean = true
        override suspend fun start(): Boolean = true
        override fun pause() { pauses.incrementAndGet() }
        override fun resume() { resumes.incrementAndGet() }
        override fun close() {}
    }

    @Test
    fun `backgrounding pauses only after the grace period`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false)
        runCurrent()
        assertEquals("no pause before grace elapses", 0, sync.pauses.get())

        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals("pause after grace", 1, sync.pauses.get())
    }

    @Test
    fun `quick foreground return within grace does not pause`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false)
        runCurrent()
        advanceTimeBy(grace / 2)
        controller.onForegroundChanged(true)
        advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals("never paused", 0, sync.pauses.get())
        assertEquals("never paused so no resume needed", 0, sync.resumes.get())
    }

    @Test
    fun `resume after a completed pause`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(1, sync.pauses.get())

        controller.onForegroundChanged(true)
        runCurrent()
        assertEquals("resume on foreground", 1, sync.resumes.get())
    }

    @Test
    fun `losing network pauses, regaining it resumes`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onNetworkChanged(false)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(1, sync.pauses.get())

        controller.onNetworkChanged(true)
        runCurrent()
        assertEquals(1, sync.resumes.get())
    }

    // ---------------------------------------------------------------------------------------
    // race / flap tests (wider-scope audit H3)
    // ---------------------------------------------------------------------------------------

    /**
     * Contract: a foreground flap (off → on → off) within the grace window must reset the
     * pause timer on the "on" transition. The pause fires exactly once, anchored at the
     * second "off" event + grace — not at the first.
     *
     * Wall clock (grace=1000ms):
     *   T=0           : foreground=false (pauseJob scheduled, fires at grace)
     *   T=grace/2     : foreground=true (cancels pauseJob, sets pauseJob=null)
     *   T=grace*0.8   : foreground=false (NEW pauseJob, fires at grace*1.8)
     *   T=grace       : assert pauses=0 — the original schedule's deadline must not fire
     *   T=2*grace+1   : assert pauses=1 — well past the new schedule's grace*1.8 deadline
     *
     * Mutation that would fail this:
     *   * Removing `pauseJob?.cancel()` on the foreground=true branch — the first pauseJob
     *     would fire at grace, producing pauses=1 at T=grace (caught by the mid-test
     *     assertion at line 129).
     *   * Removing `pauseJob = null` after cancel — the guard `pauseJob == null` on the
     *     "else if" branch would fail when the second `foreground=false` arrives, so the
     *     new pauseJob is never scheduled and pauses stays at 0 at T=2*grace+1.
     */
    @Test
    fun `foreground flap within grace re-anchors pause to the latest off`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false) // T=0
        runCurrent()
        advanceTimeBy(grace / 2)              // T=grace/2
        controller.onForegroundChanged(true)
        runCurrent()
        assertEquals("first pause was cancelled by the foreground return", 0, sync.pauses.get())

        // 30% of grace later — still well before the original schedule's deadline.
        advanceTimeBy((grace * 3) / 10)       // T=grace*0.8
        controller.onForegroundChanged(false)
        runCurrent()

        // Advance to the *original* schedule's would-have-fired time. If the cancel didn't
        // work, pause would fire here.
        advanceTimeBy(grace / 5)              // T=grace
        runCurrent()
        assertEquals("pause must not fire at the original schedule's time", 0, sync.pauses.get())

        // Now advance well past the new schedule's deadline (T=grace*1.8).
        // Cumulative wall clock: grace + (grace + 1) = 2*grace+1, comfortably past grace*1.8.
        advanceTimeBy(grace + 1)              // T=2*grace+1
        runCurrent()
        assertEquals("pause fires exactly once, anchored at the second off", 1, sync.pauses.get())
        assertEquals("no spurious resumes", 0, sync.resumes.get())
    }

    /**
     * Contract: when both foreground and network are off, regaining network alone must NOT
     * trigger resume — the synchronizer should only become active when BOTH are on. This is
     * the phone-comes-back-online-while-still-in-background scenario.
     *
     * Mutation that would fail this:
     *   * Resume guard checking `online` instead of `foreground && online`.
     *   * `onNetworkChanged(true)` calling `resume()` unconditionally.
     */
    @Test
    fun `network on while foreground off does not resume`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false)
        controller.onNetworkChanged(false)
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals("pause fired once after grace", 1, sync.pauses.get())
        assertEquals(0, sync.resumes.get())

        // Only network comes back; foreground is still off.
        controller.onNetworkChanged(true)
        runCurrent()
        assertEquals(
            "must NOT resume while foreground is still false",
            0,
            sync.resumes.get(),
        )

        // Foreground returns — now we should resume.
        controller.onForegroundChanged(true)
        runCurrent()
        assertEquals("resume fires once both signals are active again", 1, sync.resumes.get())
    }

    /**
     * Contract: a second "inactive" signal arriving while a pauseJob is already pending must
     * NOT schedule a second job. The `pauseJob == null` guard prevents double-scheduling;
     * without it, the second signal would queue another pause and `pauses` would jump to 2.
     *
     * Wall clock (grace=1000ms):
     *   T=0          : foreground=false (pauseJob #1 scheduled, fires at T=grace)
     *   T=grace/4    : network=false (under correct code: no-op; under mutant: pauseJob #2
     *                                  scheduled, would fire at T=grace*1.25)
     *   T=grace+1    : assert pauses=1 — pauseJob #1 fired; mutant's #2 has NOT fired yet
     *   T=2*grace+1  : assert pauses still 1 — mutant's #2 would have fired at grace*1.25,
     *                                          so this catches the double-schedule
     *
     * Mutation that would fail this:
     *   * Removing the `pauseJob == null` guard from the `else if (active && ...)` branch —
     *     the second inactive signal schedules a duplicate pauseJob at T=grace/4, firing at
     *     T=grace*1.25. The final assertion at T=2*grace+1 sees pauses=2 instead of 1.
     */
    @Test
    fun `second inactive signal while pause is pending does not double-schedule`() = runTest {
        val sync = FakeSynchronizer()
        val controller = LifecycleController(backgroundScope, grace) { sync }

        controller.onForegroundChanged(false)          // T=0, pauseJob scheduled
        runCurrent()
        advanceTimeBy(grace / 4)                       // T=grace/4
        controller.onNetworkChanged(false)             // still inactive — must NOT add a second pauseJob
        runCurrent()

        advanceTimeBy((grace * 3) / 4 + 1)             // T=grace+1: first pauseJob should have fired
        runCurrent()
        assertEquals(
            "pause must fire exactly once even with two consecutive inactive signals",
            1,
            sync.pauses.get(),
        )

        // Wait further to catch a delayed duplicate — none should arrive.
        advanceTimeBy(grace)
        runCurrent()
        assertEquals("no second pause fired late", 1, sync.pauses.get())
        assertEquals(0, sync.resumes.get())
    }
}
