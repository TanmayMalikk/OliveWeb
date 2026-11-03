// Users and approved supporters: stores live location key state.
package com.olive.app.location

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.EncryptionUtils
import javax.crypto.SecretKey

/** Keeps an active location-session key encrypted by Android Keystore across service restarts. */
internal class LiveLocationKeyStore(context: Context) {
    private val vault = EncryptedVault(context.applicationContext, "live-location")

    // Saves the live-location encryption key in protected local storage.
    fun save(ownerUid: String, key: SecretKey) =
        vault.update { it.put(ownerUid, EncryptionUtils.encodeKeyToString(key)) }

    // Loads the live-location encryption key from protected local storage.
    fun load(ownerUid: String): SecretKey? =
        vault.read().optString(ownerUid).takeIf(String::isNotBlank)
            ?.let(EncryptionUtils::decodeStringToKey)

    // Live location key store: Removes the selected item.
    fun remove(ownerUid: String) = vault.update { it.remove(ownerUid) }
}
