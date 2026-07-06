package com.esp32esso.tier1

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.esp32esso.tier1.data.ShotEntity
import com.esp32esso.tier1.data.ShotSamples
import com.esp32esso.tier1.ui.Esp32essoTheme
import com.esp32esso.tier1.ui.LibraryScreen
import com.esp32esso.tier1.ui.MainScreen
import com.esp32esso.tier1.ui.RankingScreen
import com.esp32esso.tier1.ui.SettingsScreen
import com.esp32esso.tier1.ui.ShotReportScreen

private enum class Screen { Main, Settings, Library, Ranking, ShotReport }

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                viewModel.connect()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Immersive sticky: hide the system status + navigation bars by default
        // and let a swipe from the edge reveal them transiently. The tablet is
        // mounted on the coffee machine, so we want the full 2560×1600 panel
        // for the UI; on phones this is equally friendly since a pull-down
        // gesture still brings the bars back.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            Esp32essoTheme {
                val uiState by viewModel.uiState.collectAsState()
                val displayShotTimeMs by viewModel.displayShotTimeMs.collectAsState()
                val pendingReport by viewModel.pendingReport.collectAsState()
                val demoMode by viewModel.demoMode.collectAsState()
                val beans by viewModel.beans.collectAsState()
                val grinders by viewModel.grinders.collectAsState()
                val waters by viewModel.waters.collectAsState()
                val recipes by viewModel.recipes.collectAsState()
                val shots by viewModel.shots.collectAsState()

                var screen by remember { mutableStateOf(Screen.Main) }
                var report by remember { mutableStateOf<ShotReport?>(null) }
                // Row id of the shot we auto-inserted on report open. Null while
                // the insert is in flight; the report screen debounces its Save
                // button until this is set.
                var currentShotId by remember { mutableStateOf<Long?>(null) }
                // True when the report is opened from a ranking-card tap: shows
                // the same layout but disables every input, hides the delete
                // button, and skips the auto-save (the shot already exists).
                var reportReadOnly by remember { mutableStateOf(false) }

                when (screen) {
                    Screen.Settings -> SettingsScreen(
                        settings = uiState.settings,
                        onApply = viewModel::applySettings,
                        onBack = { screen = Screen.Main },
                    )
                    Screen.Library -> LibraryScreen(
                        beans = beans,
                        grinders = grinders,
                        waters = waters,
                        recipes = recipes,
                        onAddBean = viewModel::upsertBean,
                        onDeleteBean = viewModel::deleteBean,
                        onAddGrinder = viewModel::upsertGrinder,
                        onDeleteGrinder = viewModel::deleteGrinder,
                        onAddWater = viewModel::upsertWater,
                        onDeleteWater = viewModel::deleteWater,
                        onAddRecipe = viewModel::upsertRecipe,
                        onDeleteRecipe = viewModel::deleteRecipe,
                        onBack = { screen = Screen.Main },
                    )
                    Screen.Ranking -> RankingScreen(
                        shots = shots,
                        beans = beans,
                        grinders = grinders,
                        onDelete = viewModel::deleteShot,
                        onOpen = { shot ->
                            // Ranking taps reopen the report in read-only mode
                            // with the persisted shot as the source of truth,
                            // so the user can inspect the graph and metadata
                            // without accidentally overwriting the row.
                            report = shotToReport(shot)
                            currentShotId = shot.id
                            reportReadOnly = true
                            screen = Screen.ShotReport
                        },
                        onBack = { screen = Screen.Main },
                    )
                    Screen.ShotReport -> {
                        val current = report
                        if (current != null) {
                            ShotReportScreen(
                                report = current,
                                shotId = currentShotId,
                                readOnly = reportReadOnly,
                                initialShot = if (reportReadOnly) {
                                    shots.firstOrNull { it.id == currentShotId }
                                } else {
                                    null
                                },
                                beans = beans,
                                grinders = grinders,
                                waters = waters,
                                recipes = recipes,
                                onAutoSave = {
                                    if (!reportReadOnly && currentShotId == null) {
                                        viewModel.autoSaveShot(current) { id ->
                                            currentShotId = id
                                        }
                                    }
                                },
                                onSave = viewModel::updateShot,
                                onDelete = {
                                    currentShotId?.let(viewModel::deleteShotById)
                                    currentShotId = null
                                    reportReadOnly = false
                                    report = null
                                    screen = Screen.Main
                                },
                                onAddBean = viewModel::upsertBean,
                                onAddGrinder = viewModel::upsertGrinder,
                                onAddWater = viewModel::upsertWater,
                                onAddRecipe = viewModel::upsertRecipe,
                                onBack = {
                                    val backToRanking = reportReadOnly
                                    currentShotId = null
                                    reportReadOnly = false
                                    report = null
                                    screen = if (backToRanking) Screen.Ranking else Screen.Main
                                },
                            )
                        } else {
                            screen = Screen.Main
                        }
                    }
                    Screen.Main -> MainScreen(
                        uiState = uiState,
                        onConnect = { requestPermissionsAndConnect() },
                        onDisconnect = viewModel::disconnect,
                        onSetpointChanged = viewModel::applySetpoint,
                        onOffsetChanged = viewModel::applyThermoblockOffset,
                        onThermoblockSetpointChanged = viewModel::applyThermoblockSetpoint,
                        onBrewToggle = viewModel::setBrewing,
                        onHeaterToggle = viewModel::setHeaterEnabled,
                        onPreinfusionChanged = viewModel::applyPreinfusion,
                        onFlush = viewModel::triggerFlush,
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenLibrary = { screen = Screen.Library },
                        onOpenRanking = { screen = Screen.Ranking },
                        onSecretDemoToggle = viewModel::toggleDemoMode,
                        onSecretShowReport = viewModel::injectDemoReport,
                        demoMode = demoMode,
                        displayShotTimeMs = displayShotTimeMs,
                    )
                }

                pendingReport?.let { candidate ->
                    if (screen == Screen.Main) {
                        ShotReportPrompt(
                            onShow = {
                                report = viewModel.consumeReport() ?: candidate
                                currentShotId = null
                                reportReadOnly = false
                                screen = Screen.ShotReport
                            },
                            onDismiss = viewModel::dismissReportPrompt,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.disconnect()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some launchers/toasts briefly re-show the system bars. Re-arm the
        // hide whenever we regain focus so the app snaps back to immersive
        // without the user having to do anything.
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun requestPermissionsAndConnect() {
        val needed =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    // Android 6..11: BLE scanning is gated behind a location
                    // permission at the OS level. Without it startScan()
                    // silently returns no results — this is the
                    // "scanning forever" symptom on the SHT-W09 tablet.
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        if (needed.isEmpty()) {
            viewModel.connect()
            return
        }
        permissionLauncher.launch(needed.toTypedArray())
    }
}

// Rehydrates a persisted shot back into the transient ShotReport shape the
// report screen consumes. Samples are decoded from the JSON snapshot captured
// at shot end; the shot window is reconstructed from the sample bounds using
// the 10 s pad the builder captured, so the report graph can shade the shot
// interval and label the axis relative to shot start.
private fun shotToReport(shot: ShotEntity): ShotReport {
    val samples = ShotSamples.decode(shot.samplesJson)
    val padMs = 10_000L
    val startUptime = samples.firstOrNull()?.uptimeMs?.plus(padMs) ?: 0L
    val endUptime = startUptime + shot.durationMs
    return ShotReport(
        durationMs = shot.durationMs,
        targetC = shot.targetC,
        preinfusionSec = shot.preinfusionSec,
        peakBar = shot.peakBar,
        avgBar = shot.avgBar,
        avgGroupC = shot.avgGroupC ?: Float.NaN,
        finalGroupC = shot.finalGroupC,
        peakGroupC = shot.peakGroupC,
        samples = samples,
        shotStartUptimeMs = startUptime,
        shotEndUptimeMs = endUptime,
    )
}

@Composable
private fun ShotReportPrompt(onShow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shot finished") },
        text = { Text("Show the brew report?") },
        confirmButton = { TextButton(onClick = onShow) { Text("Show brew report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
