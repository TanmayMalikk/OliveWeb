package com.olive.app.ui.covers.fitness

import androidx.work.impl.WorkManagerImpl
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.support.FirestoreTestDriver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FitnessAccountHydrationTest {
    private fun fixture(action:(android.content.Context,FirestoreTestDriver)->Unit) {
        val context=RuntimeEnvironment.getApplication()
        val auth=mock(FirebaseAuth::class.java)
        val user=mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn("owner")
        `when`(auth.currentUser).thenReturn(user)
        val db=FirestoreTestDriver()
        val data=mock(DataClient::class.java)
        `when`(data.putDataItem(any<PutDataRequest>())).thenReturn(Tasks.forResult(mock(DataItem::class.java)))
        WorkManagerImpl.setDelegate(mock(WorkManagerImpl::class.java))
        mockStatic(FirebaseAuth::class.java).use { authentication ->
            authentication.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            mockStatic(FirebaseFirestore::class.java).use { cloud ->
                cloud.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(db.db)
                mockStatic(Wearable::class.java).use { wear ->
                    wear.`when`<DataClient> { Wearable.getDataClient(any<android.content.Context>()) }.thenReturn(data)
                    try { action(context,db) } finally { FitnessAccountSync.bind(context,null) }
                }
            }
        }
        WorkManagerImpl.setDelegate(null)
    }
    private fun payload(type:String,value:Any)=JSONObject().put("type",type).put("value",value).toString()

    @Test
    fun savedFatSecretFoodsSyncByIdAndRemoveThroughTheExistingOutbox() = fixture { context, db ->
        val prefs = fitnessPreferences(context, "owner")
        val library = FitnessFoodLibrary(prefs)
        val food = NutritionFood("fatsecret:123:4", "Example cereal", "", "1 bowl",
            160f, 3f, 34f, 1f, FoodDataSource.FATSECRET)
        val key = "saved_food_${fitnessAccountKey(food.id)}"
        FitnessAccountSync.bind(context, "owner")
        db.emit("users/owner/fitness")
        library.setSaved(food, true)
        library.recordSearch(RecentFoodSearch("cereal", false))

        val pending = FitnessAccountSync.outbox(context, "owner")
        val savedPayload = checkNotNull(pending.getString(key, null))
        val savedRecord = JSONObject(FitnessAccountSync.decode(savedPayload) as String)
        assertEquals(food.id, savedRecord.getString("id"))
        assertFalse(savedRecord.has("food"))
        assertFalse(pending.contains("recent_food_searches"))
        assertTrue(FitnessFoodLibrary(fitnessPreferences(context, "different-owner")).savedFoods().isEmpty())

        library.remove(food.id)
        assertTrue(library.savedFoods().isEmpty())
        assertNull(FitnessAccountSync.decode(checkNotNull(pending.getString(key, null))))
    }

    @Test
    fun firstServerSnapshotHydratesCloudValuesAndQueuesOnlyLocalValuesMissingFromTheCloud()=fixture { context,db ->
        val prefs=fitnessPreferences(context,"owner")
        prefs.edit().putInt("step_goal",8000).putInt("calorie_goal",2100).apply()
        db.records["users/owner/fitness/step_goal"]=mapOf("payload" to payload("int",10000))
        FitnessAccountSync.bind(context,"owner")
        assertFalse(FitnessAccountSync.failed.value)
        db.emit("users/owner/fitness",cached=true)
        assertFalse(FitnessAccountSync.outbox(context,"owner").getBoolean("_hydrated",false))
        db.emit("users/owner/fitness")
        assertTrue(FitnessAccountSync.ready.value)
        assertEquals(10000,prefs.getInt("step_goal",0))
        val pending=FitnessAccountSync.outbox(context,"owner")
        assertTrue(pending.getBoolean("_hydrated",false))
        assertTrue(pending.contains("calorie_goal"))
        assertFalse(pending.contains("step_goal"))
        prefs.edit().putInt("step_goal",12000).apply()
        assertEquals(12000,FitnessAccountSync.decode(pending.getString("step_goal","")!!))
        db.emit("users/owner/fitness")
        assertEquals(12000,prefs.getInt("step_goal",0))
    }
    @Test
    fun switchingAccountsClosesOldCloudListenersAndKeepsWatchSessionAndPreferencesAccountScoped()=fixture { context,db ->
        FitnessAccountSync.bind(context,"owner")
        val preferences=context.getSharedPreferences(FitnessWatchSync.SYNC_PREFERENCES,0)
        val original=preferences.getString(FitnessWatchSync.SESSION_ID,null)
        FitnessAccountSync.bind(context,"different-owner")
        assertEquals("different-owner",preferences.getString(FitnessWatchSync.ACTIVE_ACCOUNT_ID,null))
        assertNotEquals(original,preferences.getString(FitnessWatchSync.SESSION_ID,null))
        assertTrue(db.listeners.first().closed)
        db.records["users/owner/fitness/step_goal"]=mapOf("payload" to payload("int",99999))
        db.emit("users/owner/fitness")
        assertFalse(fitnessPreferences(context,"different-owner").contains("step_goal"))
    }
    @Test
    fun queuedMealAndWaterChangesRemainInTheirOwnersOutboxAndGuestEntriesNeverUpload()=fixture { context,_ ->
        FitnessAccountSync.meal(context,"owner","2026-11-17","meal","{\"logId\":\"meal\"}")
        FitnessAccountSync.water(context,"owner","2026-11-17","drink","{\"logId\":\"drink\",\"millilitres\":250}")
        val pending=FitnessAccountSync.outbox(context,"owner")
        assertTrue(pending.contains("meal_2026-11-17_meal"))
        assertTrue(pending.contains("drink_2026-11-17_drink"))
        FitnessAccountSync.meal(context,"owner","2026-11-17","meal",null)
        assertNull(FitnessAccountSync.decode(pending.getString("meal_2026-11-17_meal","")!!))
        FitnessAccountSync.meal(context,"guest","2026-11-17","guest","{}")
        FitnessAccountSync.water(context,"guest","2026-11-17","guest","{}")
        assertTrue(FitnessAccountSync.outbox(context,"guest").all.isEmpty())
    }
}
