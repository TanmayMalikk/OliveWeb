package com.olive.app.data.account

import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.OpaquePasswordRecoveryEnvelope
import java.util.UUID

/**
 * Protocol interface abstracting client-side OPAQUE operations.
 * Allows pure cryptographic testing without Android WebView / FFI dependencies.
 */
internal interface OpaqueClientProtocol {
    data class RegistrationStart(
        val clientRegistrationState: String,
        val registrationRequest: String,
    )

    data class RegistrationFinish(
        val registrationRecord: String,
        val exportKey: ByteArray,
    )

    data class LoginStart(
        val clientLoginState: String,
        val startLoginRequest: String,
    )

    data class LoginFinish(
        val finishLoginRequest: String,
        val exportKey: ByteArray,
    )

    suspend fun startRegistration(password: String): RegistrationStart
    suspend fun finishRegistration(state: String, response: String, password: String): RegistrationFinish
    suspend fun startLogin(password: String): LoginStart
    suspend fun finishLogin(state: String, response: String, password: String): LoginFinish?
}

/**
 * Repository for zero-knowledge password recovery.
 *
 * Facilitates the client-side OPAQUE exchange with Cloud Functions to enroll, rotate, and unlock
 * the Account Recovery Key (ARK). The plaintext password and export key never leave the client.
 */
internal class OpaqueRecoveryRepository(
    private val callable: AccountRecoveryCallable = FirebaseAccountRecoveryCallable(),
    private val authenticator: AccountRecoveryAuthenticator = FirebaseAccountRecoveryAuthenticator(),
    private val opaqueClient: OpaqueClientProtocol,
) {
    /** Enrolls an OPAQUE password recovery failsafe for an existing unlocked ARK. */
    suspend fun enroll(
        accountId: String,
        password: String,
        recoveryKey: AccountRecoveryKey,
    ): OpaquePasswordRecoveryEnvelope {
        require(password.isNotBlank()) { "Password cannot be blank" }
        authenticator.reauthenticate(accountId, password)

        val regStart = opaqueClient.startRegistration(password)
        val beginResult = response(callable.call(BEGIN_REGISTRATION, mapOf(
            "registrationRequest" to regStart.registrationRequest,
        )))
        val registrationResponse = beginResult["registrationResponse"] as? String
            ?: error("Invalid server registration response")

        val regFinish = opaqueClient.finishRegistration(
            regStart.clientRegistrationState,
            registrationResponse,
            password,
        )

        val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
        val envelope = try {
            OpaquePasswordRecoveryEnvelope.create(
                accountId = accountId,
                opaqueRecordId = recordId,
                exportKey = regFinish.exportKey,
                recoveryKey = recoveryKey,
            )
        } finally {
            regFinish.exportKey.fill(0)
        }

        val finishResult = response(callable.call(FINISH_REGISTRATION, mapOf(
            "opaqueRecordId" to recordId,
            "registrationRecord" to regFinish.registrationRecord,
            "envelope" to envelope.toMap(),
        )))
        check(finishResult["success"] == true) { "Failed to complete password recovery registration" }

        return envelope
    }

    /** Unlocks and recovers the Account Recovery Key using the account password on a new device. */
    suspend fun unlock(
        accountId: String,
        password: String,
    ): AccountRecoveryKey {
        require(password.isNotBlank()) { "Password cannot be blank" }

        val loginStart = opaqueClient.startLogin(password)
        val beginResult = response(callable.call(BEGIN_UNLOCK, mapOf(
            "startLoginRequest" to loginStart.startLoginRequest,
        )))
        val sessionId = beginResult["sessionId"] as? String ?: error("Missing unlock session ID")
        val loginResponse = beginResult["loginResponse"] as? String ?: error("Missing server login response")

        val loginFinish = opaqueClient.finishLogin(
            loginStart.clientLoginState,
            loginResponse,
            password,
        ) ?: throw IllegalArgumentException("Incorrect recovery password")

        val finishResult = response(callable.call(FINISH_UNLOCK, mapOf(
            "sessionId" to sessionId,
            "finishLoginRequest" to loginFinish.finishLoginRequest,
        )))

        val envelopeMap = finishResult["envelope"] as? Map<*, *>
            ?: error("Missing password recovery envelope from server")
        val envelope = OpaquePasswordRecoveryEnvelope.fromMap(envelopeMap)

        return try {
            envelope.open(accountId, envelope.opaqueRecordId, loginFinish.exportKey)
        } finally {
            loginFinish.exportKey.fill(0)
        }
    }

    /** Rotates the recovery password and updates the recovery envelope atomically. */
    suspend fun rotate(
        accountId: String,
        oldPassword: String,
        newPassword: String,
        currentRecoveryKey: AccountRecoveryKey? = null,
    ): OpaquePasswordRecoveryEnvelope {
        require(newPassword.isNotBlank()) { "New password cannot be blank" }
        authenticator.reauthenticate(accountId, oldPassword)

        val activeKey = currentRecoveryKey ?: unlock(accountId, oldPassword)

        val regStart = opaqueClient.startRegistration(newPassword)
        val beginResult = response(callable.call(BEGIN_REGISTRATION, mapOf(
            "registrationRequest" to regStart.registrationRequest,
        )))
        val registrationResponse = beginResult["registrationResponse"] as? String
            ?: error("Invalid server registration response")

        val regFinish = opaqueClient.finishRegistration(
            regStart.clientRegistrationState,
            registrationResponse,
            newPassword,
        )

        val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
        val newEnvelope = try {
            OpaquePasswordRecoveryEnvelope.create(
                accountId = accountId,
                opaqueRecordId = recordId,
                exportKey = regFinish.exportKey,
                recoveryKey = activeKey,
            )
        } finally {
            regFinish.exportKey.fill(0)
        }

        val rotateResult = response(callable.call(ROTATE, mapOf(
            "opaqueRecordId" to recordId,
            "registrationRecord" to regFinish.registrationRecord,
            "envelope" to newEnvelope.toMap(),
        )))
        check(rotateResult["success"] == true) { "Failed to rotate recovery password" }

        return newEnvelope
    }

    private fun response(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: error("Invalid response from recovery service")

    companion object {
        private const val BEGIN_REGISTRATION = "opaqueRecoveryBeginRegistration"
        private const val FINISH_REGISTRATION = "opaqueRecoveryFinishRegistration"
        private const val BEGIN_UNLOCK = "opaqueRecoveryBeginUnlock"
        private const val FINISH_UNLOCK = "opaqueRecoveryFinishUnlock"
        private const val ROTATE = "opaqueRecoveryRotate"
    }
}
