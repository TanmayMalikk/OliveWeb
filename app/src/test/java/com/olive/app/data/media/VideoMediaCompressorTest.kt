package com.olive.app.data.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VideoMediaCompressorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun aSecondAudioTrackRetainsTheOriginalContainer() = runBlocking<Unit> {
        val video = videoTrack(1920, 1080)
        val audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2)
        withTracks(listOf(video, audio, audio)) { source ->
            val result = VideoMediaCompressor(context).compress(source, context.cacheDir, "video/mp4")
            assertEquals(source, result.file)
            assertEquals("video/mp4", result.mimeType)
        }
    }

    @Test
    fun oversizedFrameBuffersFallBackOnLowMemoryDevices() = runBlocking<Unit> {
        val limited = spy(context)
        val manager = mock(ActivityManager::class.java)
        `when`(manager.memoryClass).thenReturn(128)
        doReturn(manager).`when`(limited).getSystemService(Context.ACTIVITY_SERVICE)
        withTracks(listOf(videoTrack(3840, 2160))) { source ->
            val result = VideoMediaCompressor(limited).compress(source, context.cacheDir, "video/mp4")
            assertEquals(source, result.file)
            assertEquals("mp4", result.fileExtension)
        }
    }

    @Test
    fun invalidDimensionsAndMissingVideoAreRejectedBeforeTranscoding() = runBlocking<Unit> {
        withTracks(listOf(videoTrack(0, 1080))) { source ->
            val error = runCatching { VideoMediaCompressor(context).compress(source, context.cacheDir, "video/mp4") }
                .exceptionOrNull()
            assertEquals("Video dimensions are invalid", error?.message)
        }
        withTracks(emptyList()) { source ->
            val error = runCatching { VideoMediaCompressor(context).compress(source, context.cacheDir, "video/mp4") }
                .exceptionOrNull()
            assertEquals("Video track is missing", error?.message)
        }
    }

    private fun videoTrack(width: Int, height: Int) =
        MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }

    private suspend fun withTracks(tracks: List<MediaFormat>, action: suspend (File) -> Unit) {
        val source = File.createTempFile("video-fixture", ".mp4", context.cacheDir)
        source.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12))
        try {
            mockConstruction(MediaExtractor::class.java) { extractor, _ ->
                `when`(extractor.trackCount).thenReturn(tracks.size)
                tracks.forEachIndexed { index, track ->
                    `when`(extractor.getTrackFormat(index)).thenReturn(track)
                }
            }.use { extractors ->
                action(source)
                verify(extractors.constructed().single()).release()
            }
        } finally {
            source.delete()
        }
    }
}
