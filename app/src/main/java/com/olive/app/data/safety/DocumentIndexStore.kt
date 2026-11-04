package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import org.json.JSONArray
import org.json.JSONObject

data class IndexedDocuments(
    val documents: List<SafetyDocument> = emptyList(),
    val lastSyncedAt: Long? = null,
)

interface DocumentIndexDataSource {
    fun read(uid: String): IndexedDocuments
    fun replace(uid: String, documents: List<SafetyDocument>, syncedAt: Long): IndexedDocuments
    fun remove(uid: String, documentId: String)
    fun purge(uid: String)
}

/** Keystore-encrypted, account-scoped metadata index; bulk ciphertext is stored separately. */
class DocumentIndexStore(private val vault: JsonVault) : DocumentIndexDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "document-index"))

    override fun read(uid: String): IndexedDocuments {
        requireIdentity(uid)
        val account = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?: return IndexedDocuments()
        val rows = account.optJSONObject(DOCUMENTS) ?: JSONObject()
        val documents = rows.keys().asSequence().mapNotNull { id ->
            runCatching { rows.getJSONObject(id).toDocument(uid, id) }.getOrNull()
        }.sortedByDescending(SafetyDocument::createdAt).toList()
        return IndexedDocuments(documents, account.optLong(LAST_SYNCED_AT).takeIf { it > 0 })
    }

    override fun replace(uid: String, documents: List<SafetyDocument>, syncedAt: Long): IndexedDocuments {
        requireIdentity(uid)
        require(syncedAt > 0)
        val accessible = documents.filter { uid in it.allowedUsers }.distinctBy(SafetyDocument::id)
        vault.update { root ->
            val accounts = root.child(ACCOUNTS)
            val rows = JSONObject()
            accessible.forEach { rows.put(it.id, it.toIndexJson(uid)) }
            accounts.put(uid, JSONObject().put(LAST_SYNCED_AT, syncedAt).put(DOCUMENTS, rows))
        }
        return IndexedDocuments(accessible.sortedByDescending(SafetyDocument::createdAt), syncedAt)
    }

    override fun remove(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        runCatching { vault.update { root ->
            root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(DOCUMENTS)?.remove(documentId)
        } }.onFailure { vault.clear() }
    }

    override fun purge(uid: String) {
        requireIdentity(uid)
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
            .onFailure { vault.clear() }
    }

    private fun safeRoot(): JSONObject = try { vault.read() } catch (_: Exception) {
        vault.clear()
        JSONObject()
    }

    private companion object {
        const val ACCOUNTS = "accounts"
        const val DOCUMENTS = "documents"
        const val LAST_SYNCED_AT = "lastSyncedAt"
    }
}

private fun SafetyDocument.toIndexJson(viewerUid: String): JSONObject {
    requireIdentity(viewerUid, id)
    require(ownerUid.isNotBlank() && ownerUid.length <= MAX_ID_LENGTH)
    require(viewerUid in allowedUsers && allowedUsers.size <= 25)
    require(name.isNotBlank() && name.length <= 512)
    require(mimeType.isNotBlank() && mimeType.length <= 160)
    require(ciphertextSize in 0..MAX_CIPHERTEXT_BYTES)
    require(ciphertextSha256.isBlank() || ciphertextSha256.matches(SHA_256))
    val viewerDeviceKeys = deviceWrappedKeys.filterKeys { it.startsWith("$viewerUid--") }
    require(viewerDeviceKeys.size <= 25 && viewerDeviceKeys.values.all { it.length <= 4096 })
    return JSONObject()
        .put("ownerUid", ownerUid)
        .put("storagePath", storagePath.take(1024))
        .put("name", name)
        .put("mimeType", mimeType)
        .put("allowedUsers", JSONArray(allowedUsers.distinct()))
        .put("createdAt", createdAt)
        .put("objectKey", objectKey.take(1024))
        .put("encryptedMetadata", encryptedMetadata.take(64 * 1024))
        .put("viewerWrappedKey", wrappedKeys[viewerUid].orEmpty())
        .put("deviceWrappedKeys", JSONObject(viewerDeviceKeys))
        .put("ciphertextSize", ciphertextSize)
        .put("ciphertextSha256", ciphertextSha256.lowercase())
        .put("encryptionVersion", encryptionVersion)
        .put("evidence", evidence)
        .put("tags", JSONArray(tags.distinct().take(20)))
        .apply {
            ocrText?.take(MAX_OCR_TEXT)?.let { put("ocrText", it) }
            ocrConfidence?.takeIf { it.isFinite() && it in 0f..1f }?.let { put("ocrConfidence", it.toDouble()) }
            ocrSuccessful?.let { put("ocrSuccessful", it) }
        }
}

private fun JSONObject.toDocument(viewerUid: String, id: String): SafetyDocument {
    requireIdentity(viewerUid, id)
    val allowed = getJSONArray("allowedUsers").let { values ->
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.distinct()
    require(viewerUid in allowed && allowed.size <= 25)
    val accountKey = optString("viewerWrappedKey").takeIf(String::isNotBlank)
    val deviceKeys = optJSONObject("deviceWrappedKeys")?.let { json ->
        json.keys().asSequence().filter { it.startsWith("$viewerUid--") }
            .associateWith(json::getString)
    }.orEmpty()
    val size = optLong("ciphertextSize")
    val checksum = optString("ciphertextSha256")
    val tags = optJSONArray("tags")?.let { values ->
        (0 until minOf(values.length(), 20)).mapNotNull {
            values.optString(it).trim().take(80).takeIf(String::isNotBlank)
        }
    }.orEmpty().distinct()
    require(size in 0..MAX_CIPHERTEXT_BYTES)
    require(checksum.isBlank() || checksum.matches(SHA_256_LOWER))
    return SafetyDocument(
        id = id,
        ownerUid = getString("ownerUid"),
        storagePath = optString("storagePath"),
        name = getString("name"),
        mimeType = getString("mimeType"),
        allowedUsers = allowed,
        createdAt = optLong("createdAt"),
        ocrText = optString("ocrText").takeIf { has("ocrText") },
        ocrConfidence = optDouble("ocrConfidence").takeIf { has("ocrConfidence") }?.toFloat(),
        ocrSuccessful = optBoolean("ocrSuccessful").takeIf { has("ocrSuccessful") },
        objectKey = optString("objectKey"),
        encryptedMetadata = optString("encryptedMetadata"),
        wrappedKeys = accountKey?.let { mapOf(viewerUid to it) }.orEmpty(),
        ciphertextSize = size,
        ciphertextSha256 = checksum,
        encryptionVersion = optInt("encryptionVersion"),
        deviceWrappedKeys = deviceKeys,
        evidence = optBoolean("evidence"),
        tags = tags,
    )
}

private fun JSONObject.child(name: String): JSONObject =
    optJSONObject(name) ?: JSONObject().also { put(name, it) }

private fun requireIdentity(uid: String, id: String? = null) {
    require(uid.isNotBlank() && uid.length <= MAX_ID_LENGTH)
    id?.let { require(it.isNotBlank() && it.length <= MAX_ID_LENGTH) }
}

private const val MAX_ID_LENGTH = 160
private const val MAX_OCR_TEXT = 50_000
private const val MAX_CIPHERTEXT_BYTES = 250L * 1024L * 1024L
private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
private val SHA_256_LOWER = Regex("^[0-9a-f]{64}$")
