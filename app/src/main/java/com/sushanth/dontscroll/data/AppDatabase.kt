package com.sushanth.dontscroll.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BlockedApp::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockedAppDao(): BlockedAppDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(
            context: Context
        ): AppDatabase {

            return INSTANCE
                ?: synchronized(this) {

                    INSTANCE
                        ?: Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "dontscroll.db"
                        )
                            .fallbackToDestructiveMigration(dropAllTables = false)
                            .build()
                            .also {
                                INSTANCE = it
                            }
                }
        }
    }
}