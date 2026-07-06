package com.esp32esso.tier1.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.esp32esso.tier1.data.BeanEntity
import com.esp32esso.tier1.data.GrinderEntity
import com.esp32esso.tier1.data.RecipeEntity
import com.esp32esso.tier1.data.WaterEntity

@Composable
fun LibraryScreen(
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    waters: List<WaterEntity>,
    recipes: List<RecipeEntity>,
    onAddBean: (BeanEntity) -> Unit,
    onDeleteBean: (BeanEntity) -> Unit,
    onAddGrinder: (GrinderEntity) -> Unit,
    onDeleteGrinder: (GrinderEntity) -> Unit,
    onAddWater: (WaterEntity) -> Unit,
    onDeleteWater: (WaterEntity) -> Unit,
    onAddRecipe: (RecipeEntity) -> Unit,
    onDeleteRecipe: (RecipeEntity) -> Unit,
    onBack: () -> Unit,
) {
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EssoOrange)
            }
            Text(
                text = "Coffee library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        LibrarySection(title = "Beans", onAdd = { sheetTarget = SheetTarget.AddBean }) {
            if (beans.isEmpty()) EmptyRow("No beans yet")
            beans.forEach { bean ->
                LibraryRow(
                    title = bean.name,
                    subtitle = beanSubtitle(bean),
                    shareSubject = "Bean · ${bean.name}",
                    onClick = { sheetTarget = SheetTarget.EditBean(bean) },
                    onDelete = { onDeleteBean(bean) },
                )
            }
        }
        LibrarySection(title = "Grinders", onAdd = { sheetTarget = SheetTarget.AddGrinder }) {
            if (grinders.isEmpty()) EmptyRow("No grinders yet")
            grinders.forEach { g ->
                LibraryRow(
                    title = g.name,
                    subtitle = listOfNotNull(g.model.takeIf { it.isNotBlank() }, g.burrType.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    shareSubject = "Grinder · ${g.name}",
                    onClick = { sheetTarget = SheetTarget.EditGrinder(g) },
                    onDelete = { onDeleteGrinder(g) },
                )
            }
        }
        LibrarySection(title = "Waters", onAdd = { sheetTarget = SheetTarget.AddWater }) {
            if (waters.isEmpty()) EmptyRow("No waters yet")
            waters.forEach { w ->
                LibraryRow(
                    title = w.name,
                    subtitle = w.tdsPpm?.let { "$it ppm" } ?: w.notes,
                    shareSubject = "Water · ${w.name}",
                    onClick = { sheetTarget = SheetTarget.EditWater(w) },
                    onDelete = { onDeleteWater(w) },
                )
            }
        }
        LibrarySection(title = "Recipes", onAdd = { sheetTarget = SheetTarget.AddRecipe }) {
            if (recipes.isEmpty()) EmptyRow("No recipes yet")
            recipes.forEach { r ->
                LibraryRow(
                    title = r.name,
                    subtitle = recipeSubtitle(r, beans, grinders, waters),
                    shareSubject = "Recipe · ${r.name}",
                    onClick = { sheetTarget = SheetTarget.EditRecipe(r) },
                    onDelete = { onDeleteRecipe(r) },
                )
            }
        }
      }
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
}

@Composable
private fun LibrarySection(
    title: String,
    onAdd: () -> Unit,
    content: @Composable () -> Unit,
) {
    FrostCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Add")
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    shareSubject: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only the text column is clickable-for-edit; the share and delete
        // icons keep their own click surface so the row doesn't intercept them.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = EssoOnSurfaceMuted,
                )
            }
        }
        IconButton(onClick = { shareLayerAsImage(context, scope, layer, shareSubject) }) {
            Icon(Icons.Filled.Share, contentDescription = "Share", tint = EssoOrange)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = EssoError)
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = EssoOnSurfaceMuted,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun recipeSubtitle(
    r: RecipeEntity,
    beans: List<BeanEntity>,
    grinders: List<GrinderEntity>,
    waters: List<WaterEntity>,
): String {
    val bean = beans.firstOrNull { it.id == r.beanId }?.name
    val grinder = grinders.firstOrNull { it.id == r.grinderId }?.name
    val water = waters.firstOrNull { it.id == r.waterId }?.name
    val bits = mutableListOf<String>()
    if (bean != null) bits += "bean $bean"
    if (grinder != null) bits += "grinder $grinder"
    if (water != null) bits += "water $water"
    if (r.doseG != null && r.yieldG != null) bits += "${trimFloat(r.doseG)}→${trimFloat(r.yieldG)}g"
    else if (r.doseG != null) bits += "dose ${trimFloat(r.doseG)}g"
    if (r.tempC != null) bits += "${trimFloat(r.tempC)}°C"
    return bits.joinToString(" · ")
}

private fun beanSubtitle(bean: BeanEntity): String {
    val bits = mutableListOf<String>()
    if (bean.roaster.isNotBlank()) bits += bean.roaster
    if (bean.origin.isNotBlank()) bits += bean.origin
    bean.roastDateEpochDay?.let { bits += formatRoastAge(it) }
    return bits.joinToString(" · ")
}

// Show the roast date compactly with a "days off roast" hint, which is what the
// user actually cares about at brew time.
private fun formatRoastAge(epochDay: Long): String {
    val date = java.time.LocalDate.ofEpochDay(epochDay)
    val today = java.time.LocalDate.now()
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    val isoDate = date.toString()
    return when {
        days < 0L -> "roasted $isoDate (future)"
        days == 0L -> "roasted today ($isoDate)"
        days == 1L -> "roasted 1d ago ($isoDate)"
        else -> "roasted ${days}d ago ($isoDate)"
    }
}
