package com.esp32esso.tier1.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Freeform text fields keep the schema forgiving: users type roast levels and
// process methods in their own words rather than picking from a curated list
// we'd inevitably have to keep growing. Numeric setpoints stay strongly typed.

@Entity(tableName = "beans")
data class BeanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val roaster: String = "",
    val origin: String = "",
    val process: String = "",
    val roastLevel: String = "",
    // LocalDate.toEpochDay() — timezone-neutral days since 1970-01-01. Null when
    // the user did not fill in the roast date.
    val roastDateEpochDay: Long? = null,
    val notes: String = "",
)

@Entity(tableName = "grinders")
data class GrinderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val model: String = "",
    val burrType: String = "",
    val notes: String = "",
)

@Entity(tableName = "waters")
data class WaterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tdsPpm: Int? = null,
    val notes: String = "",
)

// Recipes reference the other three by id (nullable, so a recipe survives a
// bean/grinder being deleted — we just show it as "unknown" in the UI).
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val beanId: Long? = null,
    val grinderId: Long? = null,
    val waterId: Long? = null,
    val grinderSetting: String = "",
    val doseG: Float? = null,
    val yieldG: Float? = null,
    val tempC: Float? = null,
    val preinfusionSec: Float? = null,
    val notes: String = "",
)

// Persisted shot log. Grades are 0-5 (0 = ungraded). `samplesJson` holds the
// full ShotReport sample slice in the compact format described in ShotSamples
// (kept in JSON to sidestep a dedicated per-sample table for now).
@Entity(tableName = "shots")
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedAtEpochMs: Long,
    val durationMs: Long,
    val targetC: Float,
    val preinfusionSec: Float,
    val peakBar: Float,
    val avgBar: Float,
    // Nullable (rather than the NaN sentinel used elsewhere in this file) since
    // it was added via an ALTER TABLE migration — older rows have no value.
    val avgGroupC: Float? = null,
    val peakGroupC: Float,
    val finalGroupC: Float,
    // User-editable label shown atop the brew report's Grade card; blank
    // renders as "Untitled Brew" rather than persisting that literal string.
    val name: String = "",
    val beanId: Long? = null,
    val grinderId: Long? = null,
    val waterId: Long? = null,
    val recipeId: Long? = null,
    val grinderSetting: String = "",
    val doseG: Float? = null,
    val notes: String = "",
    val bitterness: Int = 0,
    val acidity: Int = 0,
    val body: Int = 0,
    val visuals: Int = 0,
    val overall: Int = 0,
    val samplesJson: String = "[]",
)
