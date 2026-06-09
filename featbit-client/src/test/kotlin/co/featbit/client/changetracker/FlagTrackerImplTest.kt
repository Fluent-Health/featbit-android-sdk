package co.featbit.client.changetracker

import app.cash.turbine.test
import co.featbit.client.model.FeatureFlag
import co.featbit.client.store.DefaultMemoryStore
import co.featbit.client.store.FlagValueChangedEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class FlagTrackerImplTest {

    private fun flag(id: String, variation: String) = FeatureFlag(id = id, variation = variation)

    @Test
    fun `global subscriber receives all changes`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe { received.add(it) }

        store.upsert(flag("a", "1"))
        store.upsert(flag("b", "2"))

        assertEquals(2, received.size)
    }

    @Test
    fun `keyed subscriber only receives its own key`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe("a") { received.add(it) }

        store.upsert(flag("a", "1"))
        store.upsert(flag("b", "2"))

        assertEquals(1, received.size)
        assertEquals("a", received[0].key)
    }

    @Test
    fun `unsubscribe stops delivery`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        val listener = co.featbit.client.store.FlagChangeListener { received.add(it) }
        tracker.subscribe(listener)
        tracker.unsubscribe(listener)

        store.upsert(flag("a", "1"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `flagChanges flow emits change events`() = runTest {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)

        tracker.flagChanges.test {
            store.upsert(flag("a", "1"))
            assertEquals(FlagValueChangedEvent("a", null, "1"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close removes listener from store`() {
        val store = DefaultMemoryStore()
        val tracker = FlagTrackerImpl(store)
        val received = CopyOnWriteArrayList<FlagValueChangedEvent>()
        tracker.subscribe { received.add(it) }

        tracker.close()
        store.upsert(flag("a", "1"))

        assertTrue(received.isEmpty())
    }
}
