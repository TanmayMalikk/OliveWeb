package com.olive.app.support

import android.app.Application
import android.content.res.Resources
import com.olive.app.R
import com.olive.app.data.account.AccountRepository
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.messaging.SecureChatRepository
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.sync.PrioritySyncCoordinator
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.session.SessionDataSources
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.*
import org.mockito.Mockito.*
import org.robolectric.RuntimeEnvironment

internal class SessionRuntimeFixture(
    role: AccountRole = AccountRole.USER,
    val documents: SafetyDocumentDataSource? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : AutoCloseable {
    private val keys = TestAndroidKeyStore.install()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val accounts = mock(AccountRepository::class.java)
    val admin = mock(com.olive.app.data.account.AdminRepository::class.java)
    val messaging = mock(MessagingRepository::class.java)
    val secureChat = mock(SecureChatRepository::class.java)
    val secureDocuments = mock(com.olive.app.data.safety.SecureDocumentRepository::class.java)
    val application: Application = spy(RuntimeEnvironment.getApplication())
    val runtime: SessionRuntime
    init {
        val resources = mock(Resources::class.java)
        val stringNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        `when`(resources.getString(anyInt())).thenAnswer { stringNames[it.getArgument<Int>(0)] ?: "String" }
        `when`(resources.getString(anyInt(), anyVarargArguments())).thenAnswer {
            stringNames[it.getArgument<Int>(0)] ?: "String"
        }
        doReturn(resources).`when`(application).getResources()
        `when`(accounts.authenticatedUid()).thenReturn("owner")
        `when`(accounts.authenticatedUsername()).thenReturn("fixture_owner")
        runtime = SessionRuntime(application, scope, SessionDataSources(
            accounts, admin, messaging, secureChat, secureDocuments, null, documents, null, null,
            null, null,
        ), localMode = false, prioritySyncOverride = PrioritySyncCoordinator(application, null))
        runtime.enrollment = DeviceEnrollment("owner", "", role)
        runtime.state.accountRole = role
        runtime.state.coverUsername = "fixture_owner"
        runtime.pinAuthorizedUid = "owner"
        runtime.pinAuthorizedEpoch = runtime.epoch
        runtime.state.screen = if (role in setOf(AccountRole.ADMIN, AccountRole.STAFF)) SessionScreen.Portal else SessionScreen.Safe
        runtime.state.page = SafePage.Messages
    }

    suspend fun await(check: () -> Boolean) {
        withTimeout(15000) { while (!check()) delay(10) }
    }

    override fun close() {
        runtime.messaging.stopMessaging()
        scope.cancel()
        keys.close()
    }

    private fun anyVarargArguments(): Array<Any> = any<Array<Any>>() ?: emptyArray()
}
