package com.esp32esso.tier1.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CoffeeDao {
    @Query("SELECT * FROM beans ORDER BY name COLLATE NOCASE")
    fun beans(): Flow<List<BeanEntity>>

    @Upsert
    suspend fun upsertBean(bean: BeanEntity): Long

    @Delete
    suspend fun deleteBean(bean: BeanEntity)

    @Query("SELECT * FROM grinders ORDER BY name COLLATE NOCASE")
    fun grinders(): Flow<List<GrinderEntity>>

    @Upsert
    suspend fun upsertGrinder(grinder: GrinderEntity): Long

    @Delete
    suspend fun deleteGrinder(grinder: GrinderEntity)

    @Query("SELECT * FROM waters ORDER BY name COLLATE NOCASE")
    fun waters(): Flow<List<WaterEntity>>

    @Upsert
    suspend fun upsertWater(water: WaterEntity): Long

    @Delete
    suspend fun deleteWater(water: WaterEntity)

    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE")
    fun recipes(): Flow<List<RecipeEntity>>

    @Upsert
    suspend fun upsertRecipe(recipe: RecipeEntity): Long

    @Delete
    suspend fun deleteRecipe(recipe: RecipeEntity)

    @Query("SELECT * FROM shots ORDER BY savedAtEpochMs DESC")
    fun shots(): Flow<List<ShotEntity>>

    @Upsert
    suspend fun upsertShot(shot: ShotEntity): Long

    @Delete
    suspend fun deleteShot(shot: ShotEntity)
}
