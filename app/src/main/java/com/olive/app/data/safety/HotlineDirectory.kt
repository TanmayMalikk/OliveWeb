// Safe-app users and support staff: lists support phone numbers for users.
package com.olive.app.data

import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import android.util.Size
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await


enum class HotlineResourceKind { Hotline, Shelter }

data class HotlineResource(
    val id: String,
    val region: String,
    val city: String,
    val name: String,
    val description: String,
    val kind: HotlineResourceKind,
    val phone: String,
    val website: String,
    val source: String,
    val phoneUri: String,
)

/** Searchable view of Lars's province-specific hotline and shelter datasets. */
class HotlineDirectory private constructor(
    val checkedOn: String,
    val resources: List<HotlineResource>,
    private val cities: Map<String, String>,
) {
    // Uses the Canadian hotline list when the selected country is Canada.
    fun isCanada(address: DeliveryAddress): Boolean {
        val country = normalize(address.country)
        return country in setOf("canada", "ca", "can", "كندا", "加拿大") ||
                (country.isBlank() && inferRegion(address) != null)
    }

    // Normalizes the province or territory name before looking up local hotlines.
    fun regionFor(address: DeliveryAddress): String? =
        if (isCanada(address)) inferRegion(address) else null

    // Finds the province or territory that matches the saved region text.
    private fun inferRegion(address: DeliveryAddress): String? {
        val province = normalize(address.province).removePrefix("ca ")
        CanadianRegion.entries.firstOrNull { region -> region.aliases.any { normalize(it) == province } }
            ?.let { return it.code }
        val postal = address.postalCode.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (postal.matches(Regex("[ABCEGHJ-NPRSTVXY][0-9][A-Z][0-9][A-Z][0-9]"))) {
            if (postal.startsWith("X")) return if (postal.take(3) in setOf(
                    "X0A",
                    "X0B",
                    "X0C"
                )
            ) "NU" else "NT"
            mapOf(
                'A' to "NL",
                'B' to "NS",
                'C' to "PE",
                'E' to "NB",
                'G' to "QC",
                'H' to "QC",
                'J' to "QC",
                'K' to "ON",
                'L' to "ON",
                'M' to "ON",
                'N' to "ON",
                'P' to "ON",
                'R' to "MB",
                'S' to "SK",
                'T' to "AB",
                'V' to "BC",
                'Y' to "YT"
            )[postal.first()]?.let { return it }
        }
        return cities[normalize(address.city)]
    }

    // Checks whether a hotline serves the selected province or territory.
    fun matching(region: String, city: String, query: String = ""): List<HotlineResource> {
        val normalizedQuery = normalize(query)
        val normalizedCity = normalize(city)
        val digits = query.filter(Char::isDigit)
        val restrictToCity = normalizedCity.isNotBlank() && resources.any {
            it.region == region && normalize(it.city) == normalizedCity
        }
        return resources.filter { item ->
            (region == "CA" || item.region == region) &&
                    (!restrictToCity || normalize(item.city) == normalizedCity) &&
                    (normalizedQuery.isBlank() || normalizedQuery.split(' ').all {
                        it in normalize("${item.name} ${item.description} ${item.city} ${item.phone} ${item.kind.name}")
                    } || (digits.isNotEmpty() && query.all { it.isDigit() || it in " +-()" } &&
                            digits in item.phone.filter(Char::isDigit)))
        }
            .sortedWith(compareBy<HotlineResource> { if (normalize(it.city) == normalizedCity) 0 else 1 }
                .thenBy { it.kind }.thenBy { it.name })
    }

    companion object {
        private const val DATASET_DATE = "2026-10-15"

        // Formats a hotline phone number for display.
        fun normalize(value: String) =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        // Reads province files from saved text.
        fun parseProvinceFiles(files: Map<String, String>): HotlineDirectory {
            val expected = CanadianRegion.entries.map { it.code }.toSet()
            require(files.keys == expected) { "A hotline dataset is required for every Canadian province and territory" }
            val cities = mutableMapOf<String, String>()
            val resources = files.toSortedMap().flatMap { (region, text) ->
                val root = JSONObject(text)
                root.keys().asSequence().toList().sorted().flatMap { city ->
                    cities[normalize(city)] = region
                    val groups = root.getJSONObject(city)
                    groups.keys().asSequence().toList().sorted().flatMap { group ->
                        val kind = when (group) {
                            "Hotlines" -> HotlineResourceKind.Hotline
                            "Shelters" -> HotlineResourceKind.Shelter
                            else -> error("Unsupported hotline group: $group")
                        }
                        val entries = groups.getJSONObject(group)
                        entries.keys().asSequence().toList().sorted().map { name ->
                            val row = entries.getJSONObject(name)
                            val phone = row.getString("Phone")
                            require(phone.matches(Regex("[0-9+() -]+")))
                            val website = secureWebsite(row.getString("url"))
                            HotlineResource(
                                id = listOf(
                                    region,
                                    city,
                                    group,
                                    name
                                ).joinToString("-") { slug(it) },
                                region = region,
                                city = city,
                                name = name,
                                description = row.getString("Desc"),
                                kind = kind,
                                phone = phone,
                                website = website,
                                source = website,
                                phoneUri = "tel:" + phone.filter { it.isDigit() || it == '+' },
                            )
                        }
                    }
                }
            }
            require(resources.map { it.id }.distinct().size == resources.size)
            return HotlineDirectory(DATASET_DATE, resources, cities)
        }

        // Adds HTTPS to a hotline website when its saved address has no scheme.
        private fun secureWebsite(value: String): String {
            val normalized = when {
                value.startsWith("https://", true) -> value
                value.startsWith("http://", true) -> "https://" + value.substringAfter("://")
                !value.contains("://") -> "https://$value"
                else -> value
            }
            val uri = URI(normalized)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank())
            return normalized
        }

        // Turns a region name into the stable key used by the hotline list.
        private fun slug(value: String) = normalize(value).replace(' ', '-')
    }
}

// Place-name aliases recognize saved addresses; translated display names are Android resources.
enum class CanadianRegion(val code: String, vararg val aliases: String) {
    AB("AB", "AB", "Alberta", "ألبرتا", "艾伯塔省"),
    BC(
        "BC",
        "BC",
        "British Columbia",
        "Colombie-Britannique",
        "Columbia Británica",
        "كولومبيا البريطانية",
        "不列颠哥伦比亚省"
    ),
    MB("MB", "MB", "Manitoba", "مانيتوبا", "马尼托巴省"),
    NB(
        "NB",
        "NB",
        "New Brunswick",
        "Nouveau-Brunswick",
        "Nuevo Brunswick",
        "نيو برونزويك",
        "新不伦瑞克省"
    ),
    NL(
        "NL",
        "NL",
        "Newfoundland and Labrador",
        "Terre-Neuve-et-Labrador",
        "Terranova y Labrador",
        "نيوفاوندلاند ولابرادور",
        "纽芬兰与拉布拉多省"
    ),
    NS("NS", "NS", "Nova Scotia", "Nouvelle-Écosse", "Nueva Escocia", "نوفا سكوشا", "新斯科舍省"),
    NT(
        "NT",
        "NT",
        "Northwest Territories",
        "Territoires du Nord-Ouest",
        "Territorios del Noroeste",
        "الأقاليم الشمالية الغربية",
        "西北地区"
    ),
    NU("NU", "NU", "Nunavut", "نونافوت", "努纳武特地区"),
    ON("ON", "ON", "Ontario", "أونتاريو", "安大略省"),
    PE(
        "PE",
        "PE",
        "PEI",
        "Prince Edward Island",
        "Île-du-Prince-Édouard",
        "Isla del Príncipe Eduardo",
        "جزيرة الأمير إدوارد",
        "爱德华王子岛省"
    ),
    QC("QC", "QC", "Quebec", "Québec", "كيبيك", "魁北克省"),
    SK("SK", "SK", "Saskatchewan", "ساسكاتشوان", "萨斯喀彻温省"),
    YT("YT", "YT", "Yukon", "Yukón", "يوكون", "育空地区")
}

// Gives the safety plan the hotline dial number it needs.
internal fun hotlineDialNumber(value: String): String? {
    if (!value.startsWith("tel:")) return null
    return value.removePrefix("tel:").substringBefore('?')
        .takeIf { it.matches(Regex("[+]?[0-9]+")) }
}

// Safe-app users and support staff: captures photos and documents for safe-app uploads.

/** CameraX adapter that writes only to app-private files supplied by the caller. */
class CameraManager(private val context: Context) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // Connects CameraX to the selected lens and the Safe App preview.
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        frontFacing: Boolean = false
    ) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        val rotation = previewView.display?.rotation
        val previewBuilder = Preview.Builder()
        val imageResolution = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(
                Size(1920, 1080),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
            )
        ).build()
        val imageBuilder =
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(imageResolution)
        val recorderBuilder =
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD))
        if (rotation != null) {
            previewBuilder.setTargetRotation(rotation)
            imageBuilder.setTargetRotation(rotation)
        }
        val preview =
            previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
        imageCapture = imageBuilder.build()
        videoCapture = VideoCapture.withOutput(recorderBuilder.build())
        cameraProvider.unbindAll()
        val selector =
            if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        boundCamera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageCapture,
            videoCapture
        )
        provider = cameraProvider
    }

    // Changes the live preview zoom and keeps the requested value inside the camera's supported range.
    fun changeZoom(multiplier: Float): Float {
        val activeCamera = checkNotNull(boundCamera) { "Camera not ready" }
        val zoomState = checkNotNull(activeCamera.cameraInfo.zoomState.value) { "Camera zoom is unavailable" }
        val requested = nextCameraZoom(
            current = zoomState.zoomRatio,
            minimum = zoomState.minZoomRatio,
            maximum = zoomState.maxZoomRatio,
            multiplier = multiplier,
        )
        activeCamera.cameraControl.setZoomRatio(requested)
        return requested
    }

    // Takes a photo and saves it to the private capture file.
    suspend fun capturePhoto(file: File): File = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor,
            // Runs the image saved callback when the safety plan finishes that step.
            object : ImageCapture.OnImageSavedCallback {
                // Runs the image saved callback when the safety plan finishes that step.
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(file)
                }

                // Reports camera errors so the capture screen can explain what went wrong.
                override fun onError(error: ImageCaptureException) {
                    file.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
        continuation.invokeOnCancellation { file.delete() }
    }

    // Starts video recording and sends the saved file back to the Safe App.
    fun startVideo(
        file: File,
        includeAudio: Boolean,
        onStarted: () -> Unit,
        onFinished: (Result<File>) -> Unit
    ) {
        check(recording == null) { "A video is already recording" }
        val capture = checkNotNull(videoCapture) { "Camera not ready" }
        val pending =
            capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        check(!includeAudio || audioGranted) {
            "Microphone permission is required to record video with sound"
        }
        recording = (if (includeAudio && audioGranted) pending.withAudioEnabled() else pending)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Start) onStarted()
                else if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.hasError()) {
                        file.delete()
                        onFinished(
                            Result.failure(
                                event.cause
                                    ?: IllegalStateException("Video capture failed (${event.error})")
                            )
                        )
                    } else onFinished(Result.success(file))
                }
            }
    }

    // Safety: Stops video.
    fun stopVideo() {
        recording?.stop()
    }

    // Stops the current video recording without keeping an unfinished capture.
    fun cancelVideo() {
        recording?.close()
        recording = null
    }

    // Stops camera recording and releases CameraX resources.
    fun unbind() {
        cancelVideo()
        provider?.unbindAll()
        provider = null
        boundCamera = null
        imageCapture = null
        videoCapture =
            null
    }

    // Safety: Releases the resources used by the feature.
    fun release() {
        unbind()
        cameraExecutor.shutdown()
    }
}

// Calculates one camera zoom step without allowing invalid or unsupported values.
internal fun nextCameraZoom(
    current: Float,
    minimum: Float,
    maximum: Float,
    multiplier: Float,
): Float {
    if (!current.isFinite() || !minimum.isFinite() || !maximum.isFinite() || !multiplier.isFinite()) {
        return minimum.coerceAtLeast(1f)
    }
    return (current * multiplier).coerceIn(minimum, maximum)
}

// Safe-app users and support staff: loads and saves safety-plan edits.

// Callable Functions verify current Auth roles and sharing on every read and write; no plaintext Firestore disk cache.
internal class SafetyPlanRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    // Calls a protected safety-plan function and returns its JSON response.
    private suspend fun call(name: String, data: Map<String, Any> = emptyMap()): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    // Watches a plan that Firestore rules have already approved for this signed-in account.
    fun observe(
        ownerUid: String,
        onUpdate: (SafetyPlan) -> Unit,
        onError: (Exception) -> Unit,
    ): ListenerRegistration = firestore.collection("safetyPlans").document(ownerUid)
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            val data = snapshot?.data ?: return@addSnapshotListener
            try {
                onUpdate(SafetyPlan.fromJson(JSONObject(data)))
            } catch (failure: Exception) {
                onError(failure)
            }
        }

    // Sends changed safety-plan fields and returns the merged server version.
    suspend fun sync(plan: SafetyPlan): SafetyPlan {
        val changes =
            plan.fields.filterValues { it.mutationId != null && it.conflictRevision == null }
                .map { (key, field) ->
                    mapOf(
                        "key" to key,
                        "value" to field.value,
                        "revision" to field.revision,
                        "mutationId" to checkNotNull(field.mutationId)
                    )
                }
        val result = call(
            "safetyPlanSync",
            mapOf(
                "ownerUid" to plan.ownerUid,
                "defaults" to plan.fields.mapValues { it.value.value },
                "changes" to changes
            )
        )
        return SafetyPlan.fromJson(result.getJSONObject("plan"))
    }

    // Opens a new plan-consult ticket and shares the current safety plan with it.
    suspend fun consult(action: String) = SafetyPlan.fromJson(
        call(
            "safetyPlanConsult",
            mapOf("action" to action)
        ).getJSONObject("plan")
    )

    // Shares the safety plan with the selected trusted contacts.
    suspend fun shareContacts(friends: List<Friend>) =
        SafetyPlan.fromJson(call("safetyPlanShareContacts", mapOf("contacts" to friends.map {
            mapOf("uid" to it.uid, "friendshipId" to it.friendshipId, "name" to it.username)
        })).getJSONObject("plan"))

    // Returns only the organization accounts that may open media attached to this user's plan.
    suspend fun mediaRecipients(): Map<String, String> {
        val rows = call("safetyPlanMediaRecipients").getJSONArray("recipients")
        return (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            row.getString("uid") to row.getString("username")
        }
    }

    // Saves which trusted contact should receive safety-plan support access.
    suspend fun setSupportContact(uid: String) {
        call("safetySupportContact", mapOf("safeContactUid" to uid))
    }

    // Loads safety plans that other users shared with this trusted contact.
    suspend fun sharedWithMe(): List<SharedSafetyPlan> {
        val plans = call("safetyPlansSharedWithMe").getJSONArray("plans")
        return (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SharedSafetyPlan(
                row.getString("ownerUid"),
                row.optString("ownerName"),
                row.optLong("updatedAt")
            )
        }
    }

    // Loads open and archived plan-consult tickets for this user.
    suspend fun consultations(): Pair<Boolean, List<SafetyConsultation>> {
        val result = call("safetyPlanConsultations")
        val plans = result.getJSONArray("plans")
        return result.getBoolean("available") to (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SafetyConsultation(
                row.getString("ownerUid"),
                row.optString("ownerName"),
                row.optString("conversationId"),
                row.optString("staffUid"),
                row.optString("staffName"),
                row.optLong("ticketNumber", -1L),
            )
        }
    }

    // Finds the staff member with the fewest active assignments.
    suspend fun available(value: Boolean) {
        call("safetyPlanAvailability", mapOf("available" to value))
    }
}
