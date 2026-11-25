package com.olive.app.data

import android.content.Context
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.SavedHotlineStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SavedHotlineStoreTest {
    private fun loadDirectory(): HotlineDirectory {
        val assets = listOf(
            File("src/main/assets/hotlines/provinces"),
            File("app/src/main/assets/hotlines/provinces"),
        ).first { it.isDirectory }
        val files = assets.listFiles { file -> file.name.matches(Regex("Resources[A-Z]{2}\\.json")) }
            .orEmpty()
            .associate { it.name.removePrefix("Resources").removeSuffix(".json") to it.readText() }
        return HotlineDirectory.parseProvinceFiles(files)
    }

    @Test
    fun isolatesAccountsHashesPersistedEntriesAndTogglesCleanly() {
        val context = RuntimeEnvironment.getApplication()
        val catalog = loadDirectory()
        val mapped = catalog.mapLocations().first()
        val shelter = catalog.resources.first { !it.hasMapLocation }

        SavedHotlineStore.clearAccount(context, "uid-alpha")
        SavedHotlineStore.clearAccount(context, "uid-beta")

        assertTrue(SavedHotlineStore.toggle(context, "uid-alpha", mapped.id))
        assertTrue(SavedHotlineStore.isSaved(context, "uid-alpha", mapped.id))
        assertFalse(SavedHotlineStore.isSaved(context, "uid-beta", mapped.id))

        assertTrue(SavedHotlineStore.toggle(context, "uid-alpha", shelter.id))
        assertEquals(
            setOf(mapped.id, shelter.id),
            SavedHotlineStore.savedIds(context, "uid-alpha", catalog.resources),
        )
        assertTrue(SavedHotlineStore.savedIds(context, "uid-beta", catalog.resources).isEmpty())

        // Verify zero plaintext UID or resource ID appears in raw SharedPreferences keys or values.
        val rawPrefs = context.getSharedPreferences("olive_saved_hotlines", Context.MODE_PRIVATE).all
        val serialized = rawPrefs.entries.joinToString("\n") { "${it.key}=${it.value}" }
        assertFalse(serialized.contains("uid-alpha"))
        assertFalse(serialized.contains(mapped.id))
        assertFalse(serialized.contains(shelter.id))

        assertFalse(SavedHotlineStore.toggle(context, "uid-alpha", mapped.id))
        assertEquals(setOf(shelter.id), SavedHotlineStore.savedIds(context, "uid-alpha", catalog.resources))

        SavedHotlineStore.clearAccount(context, "uid-alpha")
        assertTrue(SavedHotlineStore.savedIds(context, "uid-alpha", catalog.resources).isEmpty())
    }
}
