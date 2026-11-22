// Safe-app users: stores per-account saved hotline resource bookmarks without plaintext IDs on disk.
package com.olive.app.data.safety

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account-scoped local store for hearted/saved hotline directory resources.
 * Stores only salted SHA-256 digests so no plaintext service names or UIDs exist on disk.
 */
internal object SavedHotlineStore {
    private const val PREF_NAME = "olive_saved_hotlines"
    private val versionCounter = AtomicLong(0L)
    private val updatesMutable = MutableStateFlow(0L)
    val updates: StateFlow<Long> = updatesMutable.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun accountKey(uid: String): String = "acct_${sha256Hex("acct:$uid")}"

    private fun resourceToken(uid: String, resourceId: String): String =
        sha256Hex("res:$uid:$resourceId")

    private fun readTokens(context: Context, uid: String?): Set<String> {
        if (uid.isNullOrBlank()) return emptySet()
        return prefs(context).getStringSet(accountKey(uid), emptySet()).orEmpty().toSet()
    }

    /**
     * Resolves the set of saved resource IDs for [uid] from the current catalog.
     */
    fun savedIds(context: Context, uid: String?, resources: List<HotlineResource>): Set<String> {
        if (uid.isNullOrBlank() || resources.isEmpty()) return emptySet()
        val tokens = readTokens(context, uid)
        if (tokens.isEmpty()) return emptySet()
        return resources.mapNotNull { resource ->
            resource.id.takeIf { resourceToken(uid, it) in tokens }
        }.toSet()
    }

    /**
     * Returns whether [resourceId] is currently saved for [uid].
     */
    fun isSaved(context: Context, uid: String?, resourceId: String): Boolean {
        if (uid.isNullOrBlank() || resourceId.isBlank()) return false
        return resourceToken(uid, resourceId) in readTokens(context, uid)
    }

    /**
     * Explicitly sets whether [resourceId] is saved for [uid].
     */
    fun setSaved(context: Context, uid: String, resourceId: String, saved: Boolean) {
        if (uid.isBlank() || resourceId.isBlank()) return
        val key = accountKey(uid)
        val token = resourceToken(uid, resourceId)
        val existing = readTokens(context, uid).toMutableSet()
        val changed = if (saved) existing.add(token) else existing.remove(token)
        if (!changed) return
        val editor = prefs(context).edit()
        if (existing.isEmpty()) editor.remove(key) else editor.putStringSet(key, existing)
        editor.apply()
        updatesMutable.value = versionCounter.incrementAndGet()
    }

    /**
     * Toggles whether [resourceId] is saved for [uid] and returns the updated state.
     */
    fun toggle(context: Context, uid: String, resourceId: String): Boolean {
        val next = !isSaved(context, uid, resourceId)
        setSaved(context, uid, resourceId, next)
        return next
    }

    /**
     * Removes saved bookmarks for [uid], e.g. on explicit account removal.
     */
    fun clearAccount(context: Context, uid: String) {
        if (uid.isBlank()) return
        val key = accountKey(uid)
        val store = prefs(context)
        if (store.contains(key)) {
            store.edit().remove(key).apply()
            updatesMutable.value = versionCounter.incrementAndGet()
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xFF
            chars[index * 2] = hex[unsigned ushr 4]
            chars[index * 2 + 1] = hex[unsigned and 0x0F]
        }
        return String(chars)
    }
}
