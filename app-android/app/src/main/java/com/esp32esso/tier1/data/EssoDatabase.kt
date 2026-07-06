package com.esp32esso.tier1.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BeanEntity::class,
        GrinderEntity::class,
        WaterEntity::class,
        RecipeEntity::class,
        ShotEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class EssoDatabase : RoomDatabase() {
    abstract fun coffeeDao(): CoffeeDao

    companion object {
        @Volatile private var instance: EssoDatabase? = null

        // v1 -> v2: nullable roast date column on beans, stored as epoch days.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE beans ADD COLUMN roastDateEpochDay INTEGER")
            }
        }

        // v3 -> v4: link a shot to its water and recipe rows (both nullable).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN waterId INTEGER")
                db.execSQL("ALTER TABLE shots ADD COLUMN recipeId INTEGER")
            }
        }

        // v4 -> v5: per-shot coffee dose in grams (nullable, since older shots
        // never captured it).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN doseG REAL")
            }
        }

        // v5 -> v6: user-editable brew name shown on the brew report.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            }
        }

        // v6 -> v7: average group temp over the shot window. Nullable since
        // older rows never captured it.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN avgGroupC REAL")
            }
        }

        // v2 -> v3: new `shots` table for graded shot log + samples JSON blob.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        savedAtEpochMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        targetC REAL NOT NULL,
                        preinfusionSec REAL NOT NULL,
                        peakBar REAL NOT NULL,
                        avgBar REAL NOT NULL,
                        peakGroupC REAL NOT NULL,
                        finalGroupC REAL NOT NULL,
                        beanId INTEGER,
                        grinderId INTEGER,
                        grinderSetting TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        bitterness INTEGER NOT NULL DEFAULT 0,
                        acidity INTEGER NOT NULL DEFAULT 0,
                        body INTEGER NOT NULL DEFAULT 0,
                        visuals INTEGER NOT NULL DEFAULT 0,
                        overall INTEGER NOT NULL DEFAULT 0,
                        samplesJson TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent(),
                )
            }
        }

        // Double-checked locking so the first request from any thread wins and
        // subsequent requests hit the cached instance without re-entering the
        // Room builder (which is not cheap).
        fun get(context: Context): EssoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EssoDatabase::class.java,
                    "esso.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
