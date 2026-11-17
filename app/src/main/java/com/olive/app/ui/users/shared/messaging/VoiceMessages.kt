package com.olive.app.ui.users.shared.messaging

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import com.olive.app.ui.shared.Paper
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import java.util.Locale
import kotlin.math.sqrt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.olive.app.R
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import java.io.File

private const val VOICE_MESSAGE_LIMIT_MS = 5 * 60_000L
internal const val VOICE_DRAFT_PREFIX = "voice-draft-"

@Composable
internal fun VoiceMessageUploadStatus(upload: MediaUploadPresentation? = null, onRetry: (() -> Unit)? = null) {
    val failed = upload?.status == MediaTransferStatus.FAILED
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (failed) R.string.voice_send_failed else R.string.voice_sending),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (failed && onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
internal fun VoiceMessageComposer(
    enabled: Boolean,
    withMicrophonePermission: (() -> Unit) -> Unit,
    onSend: (File, String) -> Boolean,
    content: @Composable (Boolean, (@Composable () -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val recording = remember { VoiceRecording(context) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<File?>(null) }
    val recentSamples = remember { mutableStateListOf<Int>() }
    val samples = remember { VoiceSamples() }
    var mounted by remember { mutableStateOf(false) }

    fun discard() {
        recording.discard()
        draft = null
        startedAt = 0
        feedback = 0
        recentSamples.clear()
        samples.clear()
    }
    DisposableEffect(lifecycle, recording) {
        mounted = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) discard()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mounted = false
            discard()
        }
    }

    fun start() {
        if (!mounted || !enabled || startedAt != 0L || draft != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        feedback = 0
        elapsed = 0
        recentSamples.clear()
        samples.clear()
        if (recording.start()) {
            startedAt = SystemClock.elapsedRealtime()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else feedback = R.string.voice_failed
    }

    fun stop() {
        val startTime = startedAt
        if (startTime == 0L) return
        elapsed = (SystemClock.elapsedRealtime() - startTime).coerceIn(0L, VOICE_MESSAGE_LIMIT_MS).toInt()
        startedAt = 0
        draft = recording.stop()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (draft == null) {
            recording.discard()
            feedback = R.string.voice_failed
        }
    }

    val recordingStartTime = startedAt
    LaunchedEffect(recordingStartTime) {
        while (recordingStartTime != 0L && startedAt == recordingStartTime) {
            val remaining = VOICE_MESSAGE_LIMIT_MS - (SystemClock.elapsedRealtime() - recordingStartTime)
            if (remaining <= 0) {
                stop()
                break
            }
            delay(minOf(100L, remaining))
            if (startedAt != recordingStartTime) break
            elapsed = (SystemClock.elapsedRealtime() - recordingStartTime)
                .coerceIn(0L, VOICE_MESSAGE_LIMIT_MS).toInt()
            val sample = recording.amplitude()
            if (recentSamples.size == WAVEFORM_BARS) recentSamples.removeAt(0)
            recentSamples.add(sample)
            samples.add(sample)
        }
    }

    val active = startedAt != 0L
    val recordingLabel = stringResource(if (active) R.string.voice_stop else R.string.voice_record)
    BackHandler(enabled = active || draft != null, onBack = ::discard)

    Column {
        if (active || draft != null || feedback != 0) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(horizontal = 10.dp).padding(top = 8.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    if (active) {
                        val recordingStatus = stringResource(R.string.voice_recording)
                        Row(
                            modifier = Modifier.semantics { stateDescription = recordingStatus },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = ::discard) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
                            }
                            Text("${voiceTime(elapsed)} / ${voiceTime(VOICE_MESSAGE_LIMIT_MS.toInt())}",
                                style = MaterialTheme.typography.labelLarge)
                            VoiceWaveform(
                                recentSamples.toList(), 1f,
                                Modifier.weight(1f).padding(horizontal = 10.dp),
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    draft?.let { file ->
                        val duration by produceState(elapsed, file) {
                            withContext(Dispatchers.IO) { readVoiceDuration(file) }?.let { value = it }
                        }
                        val waveform = samples.waveform()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = ::discard) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.cancel))
                            }
                            VoiceAudioButton(
                                modifier = Modifier.weight(1f),
                                initialDuration = duration,
                                samples = waveform,
                                load = { ready -> ready(file) },
                            )
                            IconButton(
                                enabled = enabled,
                                onClick = {
                                    val metadata = "$duration,${waveform.joinToString("")}"
                                    if (onSend(file, metadata)) {
                                        recording.detach()
                                        discard()
                                    }
                                },
                            ) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send)) }
                        }
                    }
                    if (feedback != 0) Text(
                        stringResource(feedback), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        content(active || draft != null) {
            val tint = MaterialTheme.colorScheme.primary
            IconButton(
                onClick = {
                    if (active) stop()
                    else withMicrophonePermission(::start)
                },
                enabled = active || (enabled && draft == null),
                modifier = Modifier.size(48.dp).testTag("record_voice_message"),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (active) tint else Color.Transparent,
                ) {
                    Icon(
                        if (active) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = recordingLabel,
                        tint = when {
                            active -> MaterialTheme.colorScheme.onPrimary
                            enabled && draft == null -> tint
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                        },
                        modifier = Modifier.padding(6.dp).size(24.dp),
                    )
                }
            }
        }
    }
}

private const val WAVEFORM_BARS = 24

// Coarsen older samples as the recording grows so the waveform uses bounded memory.
private class VoiceSamples {
    private val peaks = mutableListOf<Int>()
    private var samplesPerPeak = 1
    private var seen = 0
    private var peak = 0

    fun add(sample: Int) {
        peak = maxOf(peak, sample)
        if (++seen < samplesPerPeak) return
        peaks.add(peak)
        seen = 0
        peak = 0
        if (peaks.size == WAVEFORM_BARS * 4) {
            val compacted = peaks.chunked(2).map { it.max() }
            peaks.clear()
            peaks.addAll(compacted)
            samplesPerPeak *= 2
        }
    }

    fun clear() {
        peaks.clear()
        samplesPerPeak = 1
        seen = 0
        peak = 0
    }

    fun waveform(): List<Int> {
        val samples = if (seen > 0) peaks + peak else peaks
        val count = minOf(samples.size, WAVEFORM_BARS)
        return List(count) { index ->
            val from = index * samples.size / count
            val until = (index + 1) * samples.size / count
            samples.subList(from, until).max()
        }
    }
}

private fun readVoiceDuration(file: File): Int? = runCatching {
    val metadata = MediaMetadataRetriever()
    try {
        metadata.setDataSource(file.absolutePath)
        metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
            ?.takeIf { it > 0 }
    } finally {
        metadata.release()
    }
}.getOrNull()

private fun voiceTime(millis: Int): String =
    String.format(Locale.getDefault(), "%d:%02d", millis / 60_000, millis / 1000 % 60)

@Composable
private fun VoiceWaveform(samples: List<Int>, progress: Float, modifier: Modifier, tint: Color) {
    Canvas(modifier.height(26.dp)) {
        val bars = samples.ifEmpty { List(WAVEFORM_BARS) { 0 } }
        val step = size.width / bars.size
        bars.forEachIndexed { index, level ->
            val x = (index + .5f) * step
            val height = size.height * (.15f + level * .85f / 9f)
            drawLine(
                color = tint.copy(alpha = if (index < progress * bars.size) 1f else .4f),
                start = Offset(x, (size.height - height) / 2),
                end = Offset(x, (size.height + height) / 2),
                strokeWidth = minOf(2.dp.toPx(), step * .55f),
                cap = StrokeCap.Round,
            )
        }
    }
}

private class VoiceRecording(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    @Suppress("DEPRECATION")
    fun start(): Boolean = runCatching {
        val output = File.createTempFile(VOICE_DRAFT_PREFIX, ".m4a", context.cacheDir)
        file = output
        val capture = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = capture
        capture.setAudioSource(MediaRecorder.AudioSource.MIC)
        capture.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        capture.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        capture.setAudioChannels(1)
        capture.setAudioEncodingBitRate(32_000)
        capture.setAudioSamplingRate(24_000)
        capture.setOutputFile(output.absolutePath)
        capture.prepare()
        capture.start()
    }.onFailure { discard() }.isSuccess

    fun stop(): File? {
        val capture = recorder ?: return null
        recorder = null
        val stopped = runCatching { capture.stop() }.isSuccess
        capture.release()
        return file?.takeIf { stopped && it.length() > 0 }
    }

    fun amplitude(): Int = runCatching {
        val peak = recorder?.maxAmplitude ?: 0
        (sqrt(peak / 32767f) * 9).toInt().coerceIn(0, 9)
    }.getOrDefault(0)

    fun detach() { file = null }

    fun discard() {
        recorder?.release()
        recorder = null
        file?.delete()
        file = null
    }
}

@Composable
internal fun VoiceMessageBubble(mediaId: String, metadata: String, session: SessionViewModel, mine: Boolean) {
    val uid = session.coverAccountId ?: return
    val opening = mediaId in session.documents.state.openingDocumentIds
    val error = session.documents.state.attachmentErrors[mediaId]
    val (duration, samples) = remember(metadata) {
        val length = metadata.substringBefore(',').toIntOrNull()
            ?.takeIf { it > 0 && it.toLong() <= VOICE_MESSAGE_LIMIT_MS } ?: 0
        val bars = metadata.substringAfter(',', "").take(WAVEFORM_BARS).mapNotNull { it.digitToIntOrNull() }
        length to bars
    }
    VoiceAudioButton(
        opening = opening, deleteOnClose = true,
        initialDuration = duration, samples = samples,
        tint = if (mine) Paper else MaterialTheme.colorScheme.primary,
        load = { loaded ->
            session.documents.openShared(uid, mediaId) { document, file ->
                if (document.mimeType.startsWith("audio/")) loaded(file)
                else {
                    file.delete()
                    loaded(null)
                }
            }
        },
    )
    error?.let {
        Text(it, color = if (mine) Paper else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VoiceAudioButton(
    modifier: Modifier = Modifier,
    initialDuration: Int = 0,
    samples: List<Int> = emptyList(),
    tint: Color = MaterialTheme.colorScheme.primary,
    opening: Boolean = false,
    deleteOnClose: Boolean = false,
    load: ((File?) -> Unit) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var localFile by remember { mutableStateOf<File?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember(initialDuration) { mutableIntStateOf(initialDuration) }
    var position by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var failed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }

    fun release() {
        generation++
        player?.release()
        player = null
        playing = false
        position = 0
        speed = 1f
        if (deleteOnClose) localFile?.delete()
        localFile = null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) release()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release()
        }
    }
    LaunchedEffect(playing, player) {
        val current = player ?: return@LaunchedEffect
        while (playing) {
            position = runCatching { current.currentPosition }.getOrDefault(0)
            delay(80)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = !opening,
            onClick = {
                val current = player
                if (current != null) {
                    runCatching {
                        if (playing) {
                            current.pause()
                            position = current.currentPosition
                        } else current.start()
                        playing = !playing
                    }.onFailure {
                        release()
                        failed = true
                    }
                } else {
                    val token = generation
                    failed = false
                    load { file ->
                        if (token != generation) {
                            if (deleteOnClose) file?.delete()
                        } else if (file == null) {
                            failed = true
                        } else {
                            localFile = file
                            runCatching {
                                val next = MediaPlayer()
                                player = next
                                next.setDataSource(file.absolutePath)
                                next.setOnCompletionListener {
                                    playing = false
                                    position = 0
                                }
                                next.setOnErrorListener { _, _, _ ->
                                    release()
                                    failed = true
                                    true
                                }
                                next.prepare()
                                duration = next.duration.coerceAtLeast(0)
                                next.start()
                                playing = true
                            }.onFailure {
                                release()
                                failed = true
                            }
                        }
                    }
                }
            },
        ) {
            if (opening) CircularProgressIndicator(Modifier.size(20.dp), color = tint)
            else Icon(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                stringResource(if (playing) R.string.voice_pause else R.string.voice_play),
                tint = tint,
            )
        }
        Column(Modifier.weight(1f).widthIn(min = 64.dp).padding(end = 4.dp)) {
            VoiceWaveform(
                samples, if (duration > 0) position.toFloat() / duration else 0f,
                Modifier.fillMaxWidth(), tint,
            )
            Text(
                when {
                    failed -> stringResource(R.string.voice_play_failed)
                    duration <= 0 -> stringResource(R.string.voice_message)
                    else -> voiceTime(if (playing || position > 0) position else duration)
                },
                color = tint,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (playing) {
            val speedLabel = when (speed) {
                1.5f -> "1.5×"
                2f -> "2×"
                else -> "1×"
            }
            val speedDescription = stringResource(R.string.voice_playback_speed, speedLabel)
            TextButton(
                onClick = {
                    val nextSpeed = when (speed) {
                        1f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                    val current = player ?: return@TextButton
                    runCatching { current.playbackParams = current.playbackParams.setSpeed(nextSpeed) }
                        .onSuccess { speed = nextSpeed }
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.width(48.dp).semantics {
                    contentDescription = speedDescription
                },
            ) { Text(speedLabel, color = tint, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
