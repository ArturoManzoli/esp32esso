package com.esp32esso.tier1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.data.BeanEntity
import com.esp32esso.tier1.data.GrinderEntity
import com.esp32esso.tier1.data.RecipeEntity
import com.esp32esso.tier1.data.WaterEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// One surface, four form flavors, add or edit. Consumers state which target is
// active by picking a variant of this sealed interface; Edit variants carry the
// entity so the form can prefill and preserve the row id on save.
sealed interface SheetTarget {
    data object AddBean : SheetTarget
    data class EditBean(val entity: BeanEntity) : SheetTarget
    data object AddGrinder : SheetTarget
    data class EditGrinder(val entity: GrinderEntity) : SheetTarget
    data object AddWater : SheetTarget
    data class EditWater(val entity: WaterEntity) : SheetTarget
    data object AddRecipe : SheetTarget
    data class EditRecipe(val entity: RecipeEntity) : SheetTarget
}

// Frosted-glass container for the bottom-sheet body. Real gaussian blur needs
// API 31 (RenderEffect); minSdk is 26, so this is a translucent coffee-tinted
// gradient that harmonises with the app's brown → orange backdrop.
private val SheetBackground: Brush
    get() = Brush.verticalGradient(
        listOf(EssoBgBrown.copy(alpha = 0.94f), EssoBgEspresso.copy(alpha = 0.98f)),
    )

// The single reusable Add/Edit sheet. Callers wire only the save handlers they
// care about (recipe forms also need the dropdown source lists).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    target: SheetTarget,
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    waters: List<WaterEntity>,
    onSaveBean: (BeanEntity) -> Unit,
    onSaveGrinder: (GrinderEntity) -> Unit,
    onSaveWater: (WaterEntity) -> Unit,
    onSaveRecipe: (RecipeEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(SheetBackground).padding(20.dp)) {
            when (target) {
                SheetTarget.AddBean -> BeanForm(null, { onSaveBean(it); onDismiss() }, onDismiss)
                is SheetTarget.EditBean -> BeanForm(target.entity, { onSaveBean(it); onDismiss() }, onDismiss)
                SheetTarget.AddGrinder -> GrinderForm(null, { onSaveGrinder(it); onDismiss() }, onDismiss)
                is SheetTarget.EditGrinder -> GrinderForm(target.entity, { onSaveGrinder(it); onDismiss() }, onDismiss)
                SheetTarget.AddWater -> WaterForm(null, { onSaveWater(it); onDismiss() }, onDismiss)
                is SheetTarget.EditWater -> WaterForm(target.entity, { onSaveWater(it); onDismiss() }, onDismiss)
                SheetTarget.AddRecipe -> RecipeForm(null, beans, grinders, waters, { onSaveRecipe(it); onDismiss() }, onDismiss)
                is SheetTarget.EditRecipe -> RecipeForm(target.entity, beans, grinders, waters, { onSaveRecipe(it); onDismiss() }, onDismiss)
            }
        }
    }
}

// ----- Forms (used for both add and edit; pass `initial` to prefill) -----

// `initial` is remembered as a `key` so switching from one row to another (or
// from an add to an edit sheet in the same composition) resets every field.
// On save we `.copy()` from `initial` when present to preserve the row id and
// any fields the form does not expose; otherwise we build a fresh entity.

@Composable
internal fun BeanForm(
    initial: BeanEntity?,
    onSave: (BeanEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var roaster by remember(initial) { mutableStateOf(initial?.roaster.orEmpty()) }
    var origin by remember(initial) { mutableStateOf(initial?.origin.orEmpty()) }
    var process by remember(initial) { mutableStateOf(initial?.process.orEmpty()) }
    var roastLevel by remember(initial) { mutableStateOf(initial?.roastLevel.orEmpty()) }
    var roastDate by remember(initial) {
        mutableStateOf(initial?.roastDateEpochDay?.let { LocalDate.ofEpochDay(it) })
    }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    FormColumn(title = if (initial == null) "Add bean" else "Edit bean") {
        TextInput("Name", name) { name = it }
        TextInput("Roaster", roaster) { roaster = it }
        TextInput("Origin", origin) { origin = it }
        TextInput("Process", process) { process = it }
        TextInput("Roast level", roastLevel) { roastLevel = it }
        DatePickerField(
            label = "Roasting date",
            value = roastDate,
            onChange = { roastDate = it },
        )
        TextInput("Notes", notes, multiline = true) { notes = it }
        FormButtons(
            saveEnabled = name.isNotBlank(),
            onSave = {
                val base = initial ?: BeanEntity(name = "")
                onSave(
                    base.copy(
                        name = name.trim(),
                        roaster = roaster.trim(),
                        origin = origin.trim(),
                        process = process.trim(),
                        roastLevel = roastLevel.trim(),
                        roastDateEpochDay = roastDate?.toEpochDay(),
                        notes = notes.trim(),
                    ),
                )
            },
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun GrinderForm(
    initial: GrinderEntity?,
    onSave: (GrinderEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var model by remember(initial) { mutableStateOf(initial?.model.orEmpty()) }
    var burrType by remember(initial) { mutableStateOf(initial?.burrType.orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    FormColumn(title = if (initial == null) "Add grinder" else "Edit grinder") {
        TextInput("Name", name) { name = it }
        TextInput("Model", model) { model = it }
        TextInput("Burr type", burrType) { burrType = it }
        TextInput("Notes", notes, multiline = true) { notes = it }
        FormButtons(
            saveEnabled = name.isNotBlank(),
            onSave = {
                val base = initial ?: GrinderEntity(name = "")
                onSave(
                    base.copy(
                        name = name.trim(),
                        model = model.trim(),
                        burrType = burrType.trim(),
                        notes = notes.trim(),
                    ),
                )
            },
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun WaterForm(
    initial: WaterEntity?,
    onSave: (WaterEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var tds by remember(initial) { mutableStateOf(initial?.tdsPpm?.toString().orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    FormColumn(title = if (initial == null) "Add water" else "Edit water") {
        TextInput("Name", name) { name = it }
        TextInput("TDS (ppm)", tds, numeric = true) { tds = it.filter { c -> c.isDigit() } }
        TextInput("Notes", notes, multiline = true) { notes = it }
        FormButtons(
            saveEnabled = name.isNotBlank(),
            onSave = {
                val base = initial ?: WaterEntity(name = "")
                onSave(
                    base.copy(
                        name = name.trim(),
                        tdsPpm = tds.toIntOrNull(),
                        notes = notes.trim(),
                    ),
                )
            },
            onCancel = onCancel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeForm(
    initial: RecipeEntity?,
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    waters: List<WaterEntity>,
    onSave: (RecipeEntity) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var beanId by remember(initial) { mutableStateOf(initial?.beanId) }
    var grinderId by remember(initial) { mutableStateOf(initial?.grinderId) }
    var waterId by remember(initial) { mutableStateOf(initial?.waterId) }
    var grinderSetting by remember(initial) { mutableStateOf(initial?.grinderSetting.orEmpty()) }
    var dose by remember(initial) { mutableStateOf(initial?.doseG?.let { trimFloat(it) }.orEmpty()) }
    var yieldG by remember(initial) { mutableStateOf(initial?.yieldG?.let { trimFloat(it) }.orEmpty()) }
    var tempC by remember(initial) { mutableStateOf(initial?.tempC?.let { trimFloat(it) }.orEmpty()) }
    var preinf by remember(initial) { mutableStateOf(initial?.preinfusionSec?.let { trimFloat(it) }.orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    FormColumn(title = if (initial == null) "Add recipe" else "Edit recipe") {
        TextInput("Name", name) { name = it }
        LabeledDropdown("Bean", beans.map { it.id to it.name }, beanId) { beanId = it }
        LabeledDropdown("Grinder", grinders.map { it.id to it.name }, grinderId) { grinderId = it }
        LabeledDropdown("Water", waters.map { it.id to it.name }, waterId) { waterId = it }
        TextInput("Grinder setting", grinderSetting) { grinderSetting = it }
        TextInput("Dose (g)", dose, numeric = true) { dose = it }
        TextInput("Yield (g)", yieldG, numeric = true) { yieldG = it }
        TextInput("Brew temp (°C)", tempC, numeric = true) { tempC = it }
        TextInput("Pre-infusion (s)", preinf, numeric = true) { preinf = it }
        TextInput("Notes", notes, multiline = true) { notes = it }
        FormButtons(
            saveEnabled = name.isNotBlank(),
            onSave = {
                val base = initial ?: RecipeEntity(name = "")
                onSave(
                    base.copy(
                        name = name.trim(),
                        beanId = beanId,
                        grinderId = grinderId,
                        waterId = waterId,
                        grinderSetting = grinderSetting.trim(),
                        doseG = dose.toFloatOrNull(),
                        yieldG = yieldG.toFloatOrNull(),
                        tempC = tempC.toFloatOrNull(),
                        preinfusionSec = preinf.toFloatOrNull(),
                        notes = notes.trim(),
                    ),
                )
            },
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun FormColumn(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
internal fun TextInput(
    label: String,
    value: String,
    numeric: Boolean = false,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 4 else 1,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Next,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val display = value?.toString() ?: ""
    Box {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("(unknown)") },
            trailingIcon = {
                Row {
                    if (value != null) {
                        IconButton(onClick = { onChange(null) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear date", tint = EssoOnSurfaceMuted)
                        }
                    }
                    IconButton(onClick = { show = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "Pick date", tint = EssoOrange)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (show) {
        val today = remember { LocalDate.now() }
        val initial = (value ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onChange(picked)
                    }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
internal fun FormButtons(saveEnabled: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
        ) { Text("Save") }
    }
}

// Simple label + read-only exposed dropdown; used by the report screen too.
// When `enabled == false` the whole box collapses to a static text field with
// the current selection — no click target, no menu — matching the read-only
// report layout.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledDropdown(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelected: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second ?: ""
    if (!enabled) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("(library empty)") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                DropdownMenuItem(
                    text = { Text("(none)") },
                    onClick = { onSelected(null); expanded = false },
                )
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { onSelected(id); expanded = false },
                    )
                }
            }
        }
    }
}

internal fun trimFloat(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
