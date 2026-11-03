package com.tkleisas.scratchdj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqPresetStoreTest {

    private lateinit var context: Context
    private lateinit var store: EqPresetStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        store = EqPresetStore(context, TEST_DB)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun savesLoadsAndOverwritesPresets() {
        store.save("Bass boost", "v1|0|payload")
        store.save("Flat", "v1|0|flat")

        assertEquals(2, store.names().size)
        assertEquals("v1|0|payload", store.payload("Bass boost"))

        store.save("Bass boost", "v1|0|updated")
        assertEquals("v1|0|updated", store.payload("Bass boost"))
    }

    @Test
    fun deletesPresets() {
        store.save("A", "a")
        store.save("B", "b")

        store.delete("A")

        assertEquals(listOf("B"), store.names())
        assertNull(store.payload("A"))
    }

    private companion object {
        const val TEST_DB = "scratch_eq_presets_test.db"
    }
}
