package com.esp32esso.tier1.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

// Thin wrapper over CoffeeDao so ViewModels never touch Room directly. Read
// APIs return cold Flows straight from the DAO; writes are suspend functions
// invoked from viewModelScope on Dispatchers.IO (Room's default for suspend).
class CoffeeRepository(context: Context) {
    private val dao = EssoDatabase.get(context).coffeeDao()

    val beans: Flow<List<BeanEntity>> get() = dao.beans()
    val grinders: Flow<List<GrinderEntity>> get() = dao.grinders()
    val waters: Flow<List<WaterEntity>> get() = dao.waters()
    val recipes: Flow<List<RecipeEntity>> get() = dao.recipes()
    val shots: Flow<List<ShotEntity>> get() = dao.shots()

    suspend fun upsert(bean: BeanEntity): Long = dao.upsertBean(bean)
    suspend fun delete(bean: BeanEntity) = dao.deleteBean(bean)

    suspend fun upsert(grinder: GrinderEntity): Long = dao.upsertGrinder(grinder)
    suspend fun delete(grinder: GrinderEntity) = dao.deleteGrinder(grinder)

    suspend fun upsert(water: WaterEntity): Long = dao.upsertWater(water)
    suspend fun delete(water: WaterEntity) = dao.deleteWater(water)

    suspend fun upsert(recipe: RecipeEntity): Long = dao.upsertRecipe(recipe)
    suspend fun delete(recipe: RecipeEntity) = dao.deleteRecipe(recipe)

    suspend fun upsert(shot: ShotEntity): Long = dao.upsertShot(shot)
    suspend fun delete(shot: ShotEntity) = dao.deleteShot(shot)
}
