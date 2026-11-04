package com.tkleisas.scratchdj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tkleisas.scratchdj.ui.theme.ScratchThemeName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun persistsAutoplayAndCrossfadeAcrossInstances() {
        val store = SettingsStore(context, TEST_PREFS)
        store.autoplayA = false
        store.autoplayB = false
        store.crossfadeSeconds = 4.5f
        store.theme = ScratchThemeName.Midnight

        val reopened = SettingsStore(context, TEST_PREFS)

        assertFalse(reopened.autoplayA)
        assertFalse(reopened.autoplayB)
        assertEquals(4.5f, reopened.crossfadeSeconds, 1e-6f)
        assertEquals(ScratchThemeName.Midnight, reopened.theme)
    }

    private companion object {
        const val TEST_PREFS = "scratch_settings_test"
    }
}
