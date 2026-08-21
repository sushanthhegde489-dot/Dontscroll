package com.sushanth.dontscroll.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {

    @Query(
        "SELECT * FROM blocked_apps ORDER BY displayName ASC"
    )
    fun getAll(): Flow<List<BlockedApp>>

    @Query(
        "SELECT * FROM blocked_apps " +
                "WHERE packageName = :packageName " +
                "LIMIT 1"
    )
    suspend fun getByPackage(
        packageName: String
    ): BlockedApp?

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insert(
        app: BlockedApp
    )

    @Delete
    suspend fun delete(
        app: BlockedApp
    )

    @Query(
        "DELETE FROM blocked_apps " +
                "WHERE packageName = :packageName"
    )
    suspend fun deleteByPackage(
        packageName: String
    )
}