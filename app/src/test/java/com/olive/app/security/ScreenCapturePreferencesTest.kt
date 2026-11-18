package com.olive.app.security

import android.app.Application
import android.content.Context
import android.content.res.Resources
import com.olive.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ScreenCapturePreferencesTest {
    @Test
    fun preferencesPersistPerAccountWithoutLeakingAcrossSignOut() {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        val store = RuntimeEnvironment.getApplication().getSharedPreferences("capture-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        `when`(context.resources).thenReturn(resources)
        `when`(resources.getBoolean(R.bool.screen_capture_protection_enabled)).thenReturn(false)
        `when`(context.getSharedPreferences("olive_capture_privacy", Context.MODE_PRIVATE)).thenReturn(store)
        assertFalse(ScreenCapturePreferences.enabled(context, "alice"))
        ScreenCapturePreferences.setEnabled(context, "alice", true)
        assertTrue(ScreenCapturePreferences.enabled(context, "alice"))
        assertFalse(ScreenCapturePreferences.enabled(context, "bob"))
        assertFalse(ScreenCapturePreferences.enabled(context, null))
        ScreenCapturePreferences.setEnabled(context, "bob", true)
        ScreenCapturePreferences.setEnabled(context, "alice", false)
        assertFalse(ScreenCapturePreferences.enabled(context, "alice"))
        assertTrue(ScreenCapturePreferences.enabled(context, "bob"))
    }
}
