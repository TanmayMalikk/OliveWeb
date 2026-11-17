package com.olive.app.ui.users.shared.camera

import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.olive.app.data.safety.CameraManager
import com.olive.app.ocr.PaddleOcrManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraViewModelTest {
    @Test
    fun failedLensSwitchRestoresTheWorkingLensAndZoomRejectsInvalidGestures() {
        withCamera { model, camera ->
            val owner = mock(LifecycleOwner::class.java)
            val preview = mock(PreviewView::class.java)
            runBlocking {
                `when`(camera.changeZoom(1.25f)).thenReturn(1.25f)
                model.zoomIn()
                verify(camera, never()).changeZoom(anyFloat())

                model.bind(owner, preview)
                idle()
                assertTrue(model.ready.value)
                model.zoomBy(Float.NaN)
                model.zoomBy(8f)
                verify(camera).changeZoom(1.25f)
                assertEquals(1.25f, model.zoomRatio.value, 0f)
                `when`(camera.bindCamera(owner, preview, true)).thenThrow(IllegalStateException("Front lens unavailable"))
                model.flip(owner, preview)
                idle()
                assertEquals("Front lens unavailable", model.error.value)
                assertTrue(model.ready.value)
                verify(camera, times(2)).bindCamera(owner, preview, false)

                doReturn(Unit).`when`(camera).bindCamera(owner, preview, true)
                model.flip(owner, preview)
                idle()
                assertNull(model.error.value)
                assertEquals(1f, model.zoomRatio.value, 0f)
                model.leaveCamera()
                assertFalse(model.ready.value)
                verify(camera).unbind()
            }
        }
    }

    @Test
    fun videoExposesTheRecordingStateAndReportsTheResultToThePreview() {
        withCamera { model, camera ->
            val file = File("synthetic.mp4")
            var onFinished: ((Result<File>) -> Unit)? = null
            doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(2)()
                onFinished = invocation.getArgument(3)
                null
            }.`when`(camera).startVideo(
                eq(file) ?: file, eq(true),
                any<() -> Unit>() ?: {},
                any<(Result<File>) -> Unit>() ?: {},
            )
            var captured: File? = null
            model.startVideo(file, includeAudio = true) { captured = it }
            assertTrue(model.recording.value)
            assertTrue(model.busy.value)
            model.stopVideo()
            verify(camera).stopVideo()
            onFinished!!(Result.success(file))
            assertEquals(file, captured)
            assertFalse(model.recording.value)
            assertFalse(model.busy.value)

            model.startVideo(file, includeAudio = true) { fail("Failed clip must not open") }
            onFinished!!(Result.failure(IOException("Synthetic write error")))
            assertEquals("Synthetic write error", model.error.value)
            model.clearError()
            assertNull(model.error.value)
        }
    }

    @Test
    fun photoCaptureFailureClearsBusyAndFailedDocumentCaptureRemovesTheInputFile() {
        withCamera { model, camera ->
            val directory = RuntimeEnvironment.getApplication().cacheDir
            val file = File.createTempFile("camera-test-", ".jpg", directory)
            try {
                runBlocking {
                    `when`(camera.capturePhoto(file)).thenReturn(file)
                    var captured: File? = null
                    model.capturePhoto(file) { captured = it }
                    idle()
                    assertEquals(file, captured)

                    doThrow(IllegalStateException("Sensor unavailable")).`when`(camera).capturePhoto(file)
                    model.capturePhoto(file) { fail("Failed photo must not open") }
                    idle()
                    assertEquals("Sensor unavailable", model.error.value)
                    assertFalse(model.busy.value)

                    model.captureDocument(file) { _, _, _ -> fail("Failed document must not open") }
                    idle()
                    assertFalse(file.exists())
                    assertFalse(model.busy.value)
                }
            } finally {
                file.delete()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun withCamera(run: (CameraViewModel, CameraManager) -> Unit) {
        mockConstruction(CameraManager::class.java).use { cameras ->
            mockConstruction(PaddleOcrManager::class.java).use {
                val model = CameraViewModel(RuntimeEnvironment.getApplication())
                run(model, cameras.constructed().single())
            }
        }
    }
}
