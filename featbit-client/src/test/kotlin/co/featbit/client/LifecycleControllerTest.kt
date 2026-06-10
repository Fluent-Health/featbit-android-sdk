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
}
