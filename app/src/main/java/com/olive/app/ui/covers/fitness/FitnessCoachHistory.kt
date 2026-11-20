package com.olive.app.ui.covers.fitness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class FitnessCoachHistory(context: Context, accountId: String) {
    private val appContext = context.applicationContext
    private val owner = fitnessAccountKey(accountId)

    /**
     * Loads the fitness coach history.
     *
     * @return List of resulting load items.
     */
    fun load(): List<CoachTurn> = HistoryDatabase(appContext).use { database ->
        database.readableDatabase.query(
            "exchanges",
            arrayOf("question", "reply"),
            "owner = ?",
            arrayOf(owner),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(CoachTurn("user", cursor.getString(0)))
                    add(CoachTurn("model", cursor.getString(1)))
                }
            }
        }
    }

    /**
     * Appends the fitness coach history.
     *
     * @param question Question supplied to [append].
     * @param reply Reply supplied to [append].
     */
    fun append(question: String, reply: String) {
        HistoryDatabase(appContext).use { database ->
            val exchange = ContentValues().apply {
                put("owner", owner)
                put("question", question)
                put("reply", reply)
            }
            database.writableDatabase.insertOrThrow("exchanges", null, exchange)
        }
    }

    private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "fitness_coach.db", null, 1) {
        /**
         * Initializes the component and sets up its state or UI when created by Android.
         *
         * @param db DB supplied to [onCreate].
         */
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE exchanges (
                    id INTEGER PRIMARY KEY,
                    owner TEXT NOT NULL,
                    question TEXT NOT NULL,
                    reply TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX exchanges_owner ON exchanges (owner, id)")
        }

        /**
         * Handles the upgrade event.
         *
         * @param db DB supplied to [onUpgrade].
         * @param oldVersion Old version supplied to [onUpgrade].
         * @param newVersion New version supplied to [onUpgrade].
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
