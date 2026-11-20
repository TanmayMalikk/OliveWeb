package com.olive.app.ui.covers.fitness

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitnessCoachClientTest {
    @Test fun callableRequestsAreAccountScopedAndLimitFitnessContext() = runBlocking<Unit> {
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        val functions = mock(FirebaseFunctions::class.java)
        val billing = mock(HttpsCallableReference::class.java)
        val coaching = mock(HttpsCallableReference::class.java)
        fun result(payload: Any) = Tasks.forResult(
            HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(payload)
        )
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("coach-account")
        `when`(functions.getHttpsCallable("fitnessAiBilling")).thenReturn(billing)
        `when`(functions.getHttpsCallable("fitnessAiCoach")).thenReturn(coaching)
        val status = result(mapOf("active" to true, "canManage" to false))
        val portal = result(mapOf("url" to "https://billing.stripe.com/p/session"))
        val unexpectedPortal = result(mapOf("url" to "https://other.example/p/session"))
        val checkout = result(mapOf("publishableKey" to "pk_fixture", "clientSecret" to "pi_fixture_secret_fixture"))
        val invalidCheckout = result(mapOf("publishableKey" to "wrong", "clientSecret" to "wrong"))
        `when`(billing.call(mapOf("action" to "status"))).thenReturn(status)
        `when`(billing.call(mapOf("action" to "portal"))).thenReturn(portal, unexpectedPortal)
        `when`(billing.call(mapOf("action" to "paymentSheet"))).thenReturn(checkout, invalidCheckout)
        `when`(coaching.call(any<Any>() ?: emptyMap<String, Any>())).thenAnswer {
            val payload = it.getArgument<Map<String, Any>>(0)
            assertEquals(setOf("message", "conversation", "goals", "history"), payload.keys)
            assertEquals("How am I doing?", payload["message"])
            assertEquals(4, (payload["conversation"] as List<*>).size)
            assertEquals(14, (payload["history"] as List<*>).size)
            result(mapOf("reply" to "Try 2000 steps.", "goals" to mapOf("steps" to 2000, "extra" to 5, "fat" to "bad")))
        }
        mockStatic(FirebaseAuth::class.java).use { authSdk ->
            authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            val client = FitnessCoachClient("coach-account") { functions }
            assertEquals(CoachMembership(true, false), client.membership())
            assertEquals("billing.stripe.com", client.billingPortal().host)
            assertTrue(runCatching { client.billingPortal() }.isFailure)
            assertEquals("pi_fixture_secret_fixture", client.paymentSheet().clientSecret)
            assertTrue(runCatching { client.paymentSheet() }.isFailure)
            val turns = List(10) { CoachTurn("user", "Turn $it") }
            val days = List(20) {
                FitnessHistoryDay(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), 1000, 1f, 50f, 2000f, 80f, 300f, 60f)
            }
            assertEquals(CoachReply("Try 2000 steps.", mapOf("steps" to 2000)),
                client.send("How am I doing?", turns, FitnessProfile(), days))
            `when`(user.uid).thenReturn("different-account")
            assertTrue(runCatching { client.membership() }.isFailure)
            verify(billing, times(1)).call(mapOf("action" to "status"))
        }
    }
}
