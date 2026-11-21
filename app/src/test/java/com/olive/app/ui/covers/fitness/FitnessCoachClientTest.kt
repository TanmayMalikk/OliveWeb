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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitnessCoachClientTest {
    @Test fun coachReadsFourteenDaysOfLoggedMealsAndLeavesUnloggedValuesMissing() {
        val store = FitnessStore(RuntimeEnvironment.getApplication(), "guest")
        val today = LocalDate.of(2026, 11, 20)
        val oldest = today.minusDays(13)
        val food = NutritionFood("fixture-oats", "Oats", "", "1 bowl", 150f, 5f, 27f, 3f, FoodDataSource.MANUAL)
        store.addFood(food, 1f, MealType.BREAKFAST, oldest.minusDays(1))
        store.addFood(food, 2f, MealType.BREAKFAST, oldest)
        store.addFood(food, 1f, MealType.LUNCH, oldest)

        val history = store.coachHistory(today)
        assertEquals(listOf(oldest, today), history.map { it.date })
        assertEquals(450f, history.first().nutrition?.calories)
        assertEquals(listOf("Oats"), history.first().foodNames)
        assertNull(history.first().steps)
        assertNull(history.last().nutrition)
    }

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
            val history = payload["history"] as List<*>
            assertEquals(14, history.size)
            assertEquals(mapOf("date" to "2026-01-03", "foodNames" to listOf("Oats")), history.first())
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
            val days = List(16) {
                FitnessCoachDay(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), null, null, listOf("Oats"))
            }
            assertEquals(CoachReply("Try 2000 steps.", mapOf("steps" to 2000)),
                client.send("How am I doing?", turns, FitnessProfile(), days))
            `when`(user.uid).thenReturn("different-account")
            assertTrue(runCatching { client.membership() }.isFailure)
            verify(billing, times(1)).call(mapOf("action" to "status"))
        }
    }

    @Test fun formatCoachReplyStructuresWeekdaysAndExercisesIntoBullets() {
        val raw = "Fitness Coach is AI, can make mistakes, and isn’t a fitness or nutrition expert. Here is your structured 3-day routine: Monday: Barbell Squats (3x10), Bench Press (3x10), Lat Pull-downs (3x12), Plank (3x60s). Wednesday: Romanian Deadlifts (3x10), Overhead Dumbbell Press (3x10), Seated Cable Rows (3x12), Glute Bridges (3x15). Friday Leg Press (3x12), Incline Dumbbell Press (3x10), Face Pulls (3x15), Russian Twists (3x20). Rest 60-90s between sets."
        val formatted = formatCoachReply(raw)
        assertFalse(formatted.contains("Fitness Coach is AI", ignoreCase = true))
        assertTrue(formatted.startsWith("Here is your structured 3-day routine:\n\nMonday:"))
        assertTrue(formatted.contains("Monday:\n• Barbell Squats (3x10)\n• Bench Press (3x10)\n• Lat Pull-downs (3x12)\n• Plank (3x60s)"))
        assertTrue(formatted.contains("Wednesday:\n• Romanian Deadlifts (3x10)\n• Overhead Dumbbell Press (3x10)\n• Seated Cable Rows (3x12)\n• Glute Bridges (3x15)"))
        assertTrue(formatted.contains("Friday:\n• Leg Press (3x12)\n• Incline Dumbbell Press (3x10)\n• Face Pulls (3x15)\n• Russian Twists (3x20)"))
        assertTrue(formatted.endsWith("\n\nRest 60-90s between sets."))
    }

    @Test fun formatCoachReplyHandlesDayNumbersAndMeals() {
        val rawWorkout = "Day 1: 3 sets of 12 bodyweight squats, 3 sets of 10 push-ups, 3 sets of 15-second planks. Day 2: 3 sets of 10 alternating lunges, 3 sets of 10 dumbbell rows. Repeat this cycle."
        val formattedWorkout = formatCoachReply(rawWorkout)
        assertTrue(formattedWorkout.contains("Day 1:\n• 3 sets of 12 bodyweight squats\n• 3 sets of 10 push-ups\n• 3 sets of 15-second planks"))
        assertTrue(formattedWorkout.contains("Day 2:\n• 3 sets of 10 alternating lunges\n• 3 sets of 10 dumbbell rows"))
        assertTrue(formattedWorkout.endsWith("\n\nRepeat this cycle."))

        val rawMeals = "Breakfast: Oatmeal with protein. Lunch: Chicken with rice. Dinner: Salmon with veggies. Snacks: Almonds."
        val formattedMeals = formatCoachReply(rawMeals)
        assertTrue(formattedMeals.contains("• Breakfast: Oatmeal with protein."))
        assertTrue(formattedMeals.contains("• Lunch: Chicken with rice."))
        assertTrue(formattedMeals.contains("• Dinner: Salmon with veggies."))
        assertTrue(formattedMeals.contains("• Snacks: Almonds."))
    }
}
