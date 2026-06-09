package co.featbit.client.store

import co.featbit.client.model.FeatureFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DefaultMemoryStoreTest {

    private fun flag(id: String, variation: String) =
        FeatureFlag(id = id, variation = variation)

    @Test
    fun `bootstrap is loaded`() {
        val store = DefaultMemoryStore(listOf(flag("a", "1"), flag("b", "2")))
        assertEquals("1", store.get("a")?.variation)
        assertEquals(2, store.getAll().size)
        assertNull(store.get("missing"))
    }

    @Test
    fun `upsert of new flag fires event with null old value`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsert(flag("a", "1"))

        assertEquals(1, events.size)
        assertEquals(FlagValueChangedEvent("a", null, "1"), events[0])
    }

    @Test
    fun `upsert with changed value fires event, unchanged does not`() {
        val store = DefaultMemoryStore(listOf(flag("a", "1")))
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        store.addChangeListener { events.add(it) }

        store.upsert(flag("a", "1")) // unchanged
        assertTrue(events.isEmpty())

        store.upsert(flag("a", "2")) // changed
        assertEquals(listOf(FlagValueChangedEvent("a", "1", "2")), events.toList())
    }

    @Test
    fun `removed listener stops receiving events`() {
        val store = DefaultMemoryStore()
        val events = CopyOnWriteArrayList<FlagValueChangedEvent>()
        val listener = FlagChangeListener { events.add(it) }
        store.addChangeListener(listener)
        store.removeChangeListener(listener)

        store.upsert(flag("a", "1"))
        assertTrue(events.isEmpty())
    }
}
