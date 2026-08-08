package com.esp32esso.tier1.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.ShotReport
import com.esp32esso.tier1.data.BeanEntity
import com.esp32esso.tier1.data.GrinderEntity
import com.esp32esso.tier1.data.RecipeEntity
import com.esp32esso.tier1.data.ShotEntity
import com.esp32esso.tier1.data.WaterEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Post-shot detail sheet.
//
// New-shot flow: the report is auto-saved into the shot log the moment this
// screen mounts (via `onAutoSave`); the returned id lets subsequent edits
// update the same row on Save. The Delete icon removes that row again if the
// user doesn't want to keep this shot in the ranking.
//
// Read-only flow (`readOnly = true`): opened from the ranking card; the saved
// `initialShot` prefills every field, autosave is skipped, and every input +
// the Save control is hidden or disabled by default. A pencil icon in the top
// bar lifts that lock for the rest of this screen visit (`editing`), revealing
// the same inputs and Save control the new-shot flow uses so a past report can
// be corrected. Saving in that case keeps the shot's original `savedAtEpochMs`
// (see buildEntity) so fixing a typo days later doesn't reshuffle the ranking
// list or relabel when the brew actually happened. The user can still share
// the report as an image, or delete it outright, via the top bar actions — the
// ranking card itself carries no per-row actions anymore.
@Composable
fun ShotReportScreen(
    report: ShotReport,
    shotId: Long?,
    readOnly: Boolean,
    initialShot: ShotEntity?,
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    waters: List<WaterEntity>,
    recipes: List<RecipeEntity>,
    onAutoSave: () -> Unit,
    onSave: (ShotEntity) -> Unit,
    onDelete: () -> Unit,
    onAddBean: (BeanEntity) -> Unit,
    onAddGrinder: (GrinderEntity) -> Unit,
    onAddWater: (WaterEntity) -> Unit,
    onAddRecipe: (RecipeEntity) -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var beanId by remember(initialShot?.id) { mutableStateOf(initialShot?.beanId) }
    var grinderId by remember(initialShot?.id) { mutableStateOf(initialShot?.grinderId) }
    var waterId by remember(initialShot?.id) { mutableStateOf(initialShot?.waterId) }
    var recipeId by remember(initialShot?.id) { mutableStateOf(initialShot?.recipeId) }
    var grinderSetting by remember(initialShot?.id) { mutableStateOf(initialShot?.grinderSetting.orEmpty()) }
    var doseG by remember(initialShot?.id) {
        mutableStateOf(initialShot?.doseG?.let { "%.1f".format(it) }.orEmpty())
    }
    var notes by remember(initialShot?.id) { mutableStateOf(initialShot?.notes.orEmpty()) }
    var bitterness by remember(initialShot?.id) { mutableStateOf(initialShot?.bitterness ?: 0) }
    var acidity by remember(initialShot?.id) { mutableStateOf(initialShot?.acidity ?: 0) }
    var body by remember(initialShot?.id) { mutableStateOf(initialShot?.body ?: 0) }
    var visuals by remember(initialShot?.id) { mutableStateOf(initialShot?.visuals ?: 0) }
    var overall by remember(initialShot?.id) { mutableStateOf(initialShot?.overall ?: 0) }
    var brewName by remember(initialShot?.id) { mutableStateOf(initialShot?.name.orEmpty()) }
    var renamingBrew by remember { mutableStateOf(false) }

    // Lifts the read-only lock for the rest of this screen visit once the
    // operator taps the pencil (see the top bar below). Irrelevant outside the
    // read-only flow, where every input is already editable.
    var editing by remember(initialShot?.id) { mutableStateOf(false) }
    val editable = !readOnly || editing

    // Tracks whether any field has changed since the last successful save, so
    // the header's quick-save icon can flag unsaved edits in orange. `justSaved`
    // drives a transient floppy → check animation right after a save lands.
    var dirty by remember(initialShot?.id) { mutableStateOf(false) }
    var justSaved by remember(initialShot?.id) { mutableStateOf(false) }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(1400)
            justSaved = false
        }
    }

    // A single sheet target drives the inline "+" flow; whichever selector's
    // plus icon the user tapped decides which form the sheet shows.
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }

    // Fire the initial autosave exactly once per report — but only in edit
    // mode. Read-only opens are for shots already in the library.
    LaunchedEffect(report, readOnly) {
        if (!readOnly) onAutoSave()
    }

    // Shot window passed to the graph so it can lock the x axis to shot ±10 s
    // and paint the tinted band across the shot itself.
    val shotWindow = remember(report.shotStartUptimeMs, report.shotEndUptimeMs) {
        if (report.shotEndUptimeMs > report.shotStartUptimeMs) {
            report.shotStartUptimeMs..report.shotEndUptimeMs
        } else {
            null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val shareLayer = rememberGraphicsLayer()
            val context = LocalContext.current
            val performSave: () -> Unit = save@{
                val id = shotId
                if (id == null) {
                    scope.launch { snackbar.showSnackbar("Still saving…") }
                    return@save
                }
                onSave(
                    buildEntity(
                        id = id,
                        // Editing an already-saved report keeps its original
                        // save date; only a brand-new shot gets "now".
                        savedAtEpochMs = initialShot?.savedAtEpochMs ?: System.currentTimeMillis(),
                        report = report,
                        name = brewName,
                        beanId = beanId,
                        grinderId = grinderId,
                        waterId = waterId,
                        recipeId = recipeId,
                        grinderSetting = grinderSetting,
                        doseG = doseG.toFloatOrNull(),
                        notes = notes,
                        bitterness = bitterness,
                        acidity = acidity,
                        body = body,
                        visuals = visuals,
                        overall = overall,
                    ),
                )
                dirty = false
                justSaved = true
                scope.launch { snackbar.showSnackbar("Brew saved") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EssoOrange)
                }
                Text(
                    text = when {
                        !readOnly -> "Brew report"
                        editing -> "Brew report · editing"
                        else -> "Brew report · view"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (readOnly && !editing) {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit brew report", tint = EssoOrange)
                    }
                }
                if (editable) {
                    IconButton(onClick = performSave) {
                        Crossfade(targetState = justSaved, label = "saveIcon") { saved ->
                            if (saved) {
                                Icon(Icons.Filled.Check, contentDescription = "Saved", tint = EssoGreen)
                            } else {
                                Icon(
                                    Icons.Filled.Save,
                                    contentDescription = "Save brew",
                                    tint = if (dirty) EssoOrange else EssoOnSurfaceMuted,
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = {
                    shareLayerAsImage(context, scope, shareLayer, "Brew report")
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = EssoOrange)
                }
                // Available in both modes now — the ranking card dropped its
                // own delete/share row, so this is the only place left to
                // remove a saved brew.
                IconButton(onClick = {
                    onDelete()
                    scope.launch { snackbar.showSnackbar("Brew discarded") }
                }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Delete brew",
                        tint = EssoOrange,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // Everything inside this Column is what the Share captures.
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        shareLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(shareLayer)
                    },
            ) {
                // Summary and Grade share the top row as equal half-cards; the
                // Row is pinned to the taller card's height so both fill it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FrostCard(
                        accent = true,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        // Height-matched to the Grade card's title row (which is
                        // stretched to 28 dp by its pencil IconButton) so both
                        // cards' row stacks start from the same baseline.
                        Box(
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "Summary",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SummaryRow("Duration", formatDuration(report.durationMs))
                        SummaryRow("Target", formatTempC(report.targetC))
                        SummaryRow("Pre-infusion", formatSeconds(report.preinfusionSec))
                        SummaryRow("Average pressure", formatBar(report.avgBar))
                        SummaryRow("Average group temp", formatTempC(report.avgGroupC))
                        SummaryRow("Final group temp", formatTempC(report.finalGroupC))
                    }

                    FrostCard(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = editable,
                                    onClick = {},
                                    onDoubleClick = { renamingBrew = true },
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = brewName.ifBlank { "Untitled Brew" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (editable) {
                                IconButton(
                                    onClick = { renamingBrew = true },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Rename brew",
                                        tint = EssoOrange,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // Passing onChange = null makes the row decorative, which is
                        // what we want in read-only mode.
                        val setter: (String) -> ((Int) -> Unit)? = { axis ->
                            if (!editable) null else { it: Int ->
                                when (axis) {
                                    "Bitterness" -> bitterness = it
                                    "Acidity" -> acidity = it
                                    "Body" -> body = it
                                    "Visuals" -> visuals = it
                                    "Overall" -> overall = it
                                }
                                dirty = true
                            }
                        }
                        StarRow("Bitterness", bitterness, onChange = setter("Bitterness"))
                        StarRow("Acidity", acidity, onChange = setter("Acidity"))
                        StarRow("Body", body, onChange = setter("Body"))
                        StarRow("Visuals", visuals, onChange = setter("Visuals"))
                        StarRow("Overall", overall, onChange = setter("Overall"))
                    }
                }

                FrostCard {
                    sectionLabel("Brew graph")
                    Spacer(Modifier.height(8.dp))
                    BrewGraph(
                        history = report.samples,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        shotWindow = shotWindow,
                        interactive = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    GraphLegend()
                }
            }

            // Bean/water and grinder live side by side as equal half-cards.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FrostCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    sectionLabel("Bean & water")
                    Spacer(Modifier.height(10.dp))
                    SelectorWithAdd(
                        label = "Bean",
                        options = beans.map { it.id to it.name },
                        selectedId = beanId,
                        onSelected = { beanId = it; dirty = true },
                        onAdd = { sheetTarget = SheetTarget.AddBean },
                        enabled = editable,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = doseG,
                        onValueChange = { doseG = it; dirty = true },
                        label = { Text("Coffee dose (g)") },
                        singleLine = true,
                        enabled = editable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    SelectorWithAdd(
                        label = "Water",
                        options = waters.map { it.id to it.name },
                        selectedId = waterId,
                        onSelected = { waterId = it; dirty = true },
                        onAdd = { sheetTarget = SheetTarget.AddWater },
                        enabled = editable,
                    )
                }

                FrostCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    sectionLabel("Grinder")
                    Spacer(Modifier.height(10.dp))
                    SelectorWithAdd(
                        label = "Grinder",
                        options = grinders.map { it.id to it.name },
                        selectedId = grinderId,
                        onSelected = { grinderId = it; dirty = true },
                        onAdd = { sheetTarget = SheetTarget.AddGrinder },
                        enabled = editable,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = grinderSetting,
                        onValueChange = { grinderSetting = it; dirty = true },
                        label = { Text("Grinder setting") },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Recipe (selector + a summary of the chosen one) and notes, also
            // as equal half-cards below the row above.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FrostCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    sectionLabel("Recipe")
                    Spacer(Modifier.height(10.dp))
                    SelectorWithAdd(
                        label = "Recipe",
                        options = recipes.map { it.id to it.name },
                        selectedId = recipeId,
                        onSelected = { recipeId = it; dirty = true },
                        onAdd = { sheetTarget = SheetTarget.AddRecipe },
                        enabled = editable,
                    )
                    Spacer(Modifier.height(10.dp))
                    // Falls back to the picked recipe's own fields whenever this
                    // shot hasn't overridden them — the summary always reflects
                    // exactly what a "Save as recipe" action would persist.
                    val selectedRecipe = recipes.firstOrNull { it.id == recipeId }
                    RecipeSummary(
                        report = report,
                        beanName = beans.firstOrNull { it.id == (beanId ?: selectedRecipe?.beanId) }?.name,
                        grinderName = grinders.firstOrNull { it.id == (grinderId ?: selectedRecipe?.grinderId) }?.name,
                        waterName = waters.firstOrNull { it.id == (waterId ?: selectedRecipe?.waterId) }?.name,
                        grinderSetting = grinderSetting.ifBlank { selectedRecipe?.grinderSetting.orEmpty() },
                        doseG = doseG.toFloatOrNull() ?: selectedRecipe?.doseG,
                    )
                }

                FrostCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    sectionLabel("Notes")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it; dirty = true },
                        label = { Text("Notes") },
                        minLines = 3,
                        enabled = editable,
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }

            if (editable) {
                Button(
                    onClick = performSave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = EssoOrange),
                ) { Text("Save brew") }
            }
        }
      }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    sheetTarget?.let { target ->
        LibrarySheet(
            target = target,
            beans = beans,
            grinders = grinders,
            waters = waters,
            onSaveBean = onAddBean,
            onSaveGrinder = onAddGrinder,
            onSaveWater = onAddWater,
            onSaveRecipe = onAddRecipe,
            onDismiss = { sheetTarget = null },
        )
    }

    if (renamingBrew) {
        RenameBrewDialog(
            initial = brewName,
            onConfirm = {
                brewName = it
                dirty = true
                renamingBrew = false
            },
            onDismiss = { renamingBrew = false },
        )
    }
}

@Composable
private fun RenameBrewDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename brew") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                placeholder = { Text("Untitled Brew") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// Dropdown + circular "+" button that opens the inline add sheet.
@Composable
private fun SelectorWithAdd(
    label: String,
    options: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelected: (Long?) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LabeledDropdown(
            label = label,
            options = options,
            selectedId = selectedId,
            onSelected = onSelected,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        if (enabled) {
            FilledIconButton(
                onClick = onAdd,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = EssoOrange,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF1A0E03),
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add $label")
            }
        }
    }
}

// Everything that would compose a repeatable recipe: what's already picked
// for this shot, falling back to the selected library recipe's own fields,
// plus the brew parameters straight from the report (always known, since
// they came off the machine rather than a selector).
@Composable
private fun RecipeSummary(
    report: ShotReport,
    beanName: String?,
    grinderName: String?,
    waterName: String?,
    grinderSetting: String,
    doseG: Float?,
) {
    Column {
        SummaryRow("Bean", beanName?.takeIf { it.isNotBlank() } ?: "—")
        SummaryRow("Grinder", grinderName?.takeIf { it.isNotBlank() } ?: "—")
        SummaryRow("Grinder setting", grinderSetting.ifBlank { "—" })
        SummaryRow("Water", waterName?.takeIf { it.isNotBlank() } ?: "—")
        SummaryRow("Dose", doseG?.let { "%.1f g".format(it) } ?: "—")
        SummaryRow("Brew temp", formatTempC(report.targetC))
        SummaryRow("Thermoblock offset", formatOffsetC(report.thermoblockOffsetC))
        SummaryRow("Pressure", formatBar(report.avgBar))
        SummaryRow("Pre-infusion", formatSeconds(report.preinfusionSec))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = EssoOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun buildEntity(
    id: Long,
    savedAtEpochMs: Long,
    report: ShotReport,
    name: String,
    beanId: Long?,
    grinderId: Long?,
    waterId: Long?,
    recipeId: Long?,
    grinderSetting: String,
    doseG: Float?,
    notes: String,
    bitterness: Int,
    acidity: Int,
    body: Int,
    visuals: Int,
    overall: Int,
): ShotEntity = ShotEntity(
    id = id,
    savedAtEpochMs = savedAtEpochMs,
    durationMs = report.durationMs,
    targetC = report.targetC,
    preinfusionSec = report.preinfusionSec,
    peakBar = report.peakBar,
    avgBar = report.avgBar,
    avgGroupC = report.avgGroupC.takeUnless { it.isNaN() },
    peakGroupC = report.peakGroupC,
    finalGroupC = report.finalGroupC,
    thermoblockOffsetC = report.thermoblockOffsetC.takeUnless { it.isNaN() },
    thermoblockManual = report.thermoblockManual,
    name = name,
    beanId = beanId,
    grinderId = grinderId,
    waterId = waterId,
    recipeId = recipeId,
    grinderSetting = grinderSetting,
    doseG = doseG,
    notes = notes,
    bitterness = bitterness,
    acidity = acidity,
    body = body,
    visuals = visuals,
    overall = overall,
    samplesJson = com.esp32esso.tier1.data.ShotSamples.encode(report.samples),
)

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val cs = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}

private fun formatBar(value: Float): String =
    if (value.isNaN()) "—" else "%.1f bar".format(value)

private fun formatTempC(value: Float): String =
    if (value.isNaN()) "— °C" else "%.1f °C".format(value)

private fun formatOffsetC(value: Float): String {
    if (value.isNaN()) return "— °C"
    val sign = if (value >= 0f) "+" else ""
    return "$sign%.1f °C".format(value)
}

private fun formatSeconds(value: Float): String =
    if (value <= 0.05f) "off" else "%.1f s".format(value)
