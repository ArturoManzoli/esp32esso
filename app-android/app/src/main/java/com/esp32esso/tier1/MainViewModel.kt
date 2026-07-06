package com.esp32esso.tier1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esp32esso.tier1.ble.BleUiState
import com.esp32esso.tier1.ble.ConnectionPhase
import com.esp32esso.tier1.ble.MachineSettings
import com.esp32esso.tier1.ble.MachineState
import com.esp32esso.tier1.ble.Tier1BleClient
import com.esp32esso.tier1.data.BeanEntity
import com.esp32esso.tier1.data.CoffeeRepository
import com.esp32esso.tier1.data.GrinderEntity
import com.esp32esso.tier1.data.RecipeEntity
import com.esp32esso.tier1.data.ShotEntity
import com.esp32esso.tier1.data.ShotSamples
import com.esp32esso.tier1.data.WaterEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val client = Tier1BleClient(application)
    private val repo = CoffeeRepository(application)

    // Offline developer shortcut: when on (and no real machine is linked) the
    // whole UI runs off DemoData's synthetic connected state.
    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    // Real BLE state, or the demo stand-in while demo mode is armed offline.
    val uiState: StateFlow<BleUiState> =
        combine(client.uiState, _demoMode) { real, demo ->
            if (demo && real.phase != ConnectionPhase.Connected) DemoData.uiState else real
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BleUiState())

    // Post-shot behaviour: while brewing() the timer runs live from the wire;
    // when the shot ends we hold the last time for kPostShotHoldMs, then zero
    // it and assemble a ShotReport for the user to optionally open.
    private val _displayShotTimeMs = MutableStateFlow(0L)
    val displayShotTimeMs: StateFlow<Long> = _displayShotTimeMs.asStateFlow()

    private val _pendingReport = MutableStateFlow<ShotReport?>(null)
    val pendingReport: StateFlow<ShotReport?> = _pendingReport.asStateFlow()

    private var wasBrewing = false
    private var shotStartUptimeMs: Long = 0L
    private var postShotJob: Job? = null

    // Room flows lifted to StateFlow so the UI can consume them synchronously.
    val beans: StateFlow<List<BeanEntity>> =
        repo.beans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val grinders: StateFlow<List<GrinderEntity>> =
        repo.grinders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val waters: StateFlow<List<WaterEntity>> =
        repo.waters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recipes: StateFlow<List<RecipeEntity>> =
        repo.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shots: StateFlow<List<ShotEntity>> =
        repo.shots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            client.uiState.collect { state ->
                val machine = state.machineState
                if (machine != null) trackBrew(machine)
            }
        }
    }

    private fun trackBrew(machine: MachineState) {
        val brewing = machine.brewing
        if (brewing && !wasBrewing) {
            postShotJob?.cancel()
            postShotJob = null
            shotStartUptimeMs = machine.uptimeMs - machine.shotTimeMs
            _displayShotTimeMs.value = machine.shotTimeMs
        } else if (brewing) {
            _displayShotTimeMs.value = machine.shotTimeMs
        } else if (!brewing && wasBrewing) {
            val heldTime = _displayShotTimeMs.value
            val startUptime = shotStartUptimeMs
            val endUptime = machine.uptimeMs
            val preinfusion = client.uiState.value.preinfusionSec ?: 0f
            val target = machine.groupSetpointC
            postShotJob =
                viewModelScope.launch {
                    delay(POST_SHOT_HOLD_MS)
                    _displayShotTimeMs.value = 0L
                    _pendingReport.value =
                        buildReport(
                            durationMs = heldTime,
                            startUptime = startUptime,
                            endUptime = endUptime,
                            targetC = target,
                            preinfusionSec = preinfusion,
                            history = client.uiState.value.history,
                        )
                }
        } else if (!brewing && postShotJob == null) {
            // Idle steady state: mirror what the wire reports (usually 0).
            _displayShotTimeMs.value = machine.shotTimeMs
        }
        wasBrewing = brewing
    }

    fun dismissReportPrompt() {
        _pendingReport.value = null
    }

    fun consumeReport(): ShotReport? {
        val r = _pendingReport.value
        _pendingReport.value = null
        return r
    }

    // Offline dev shortcut #1 (triple-tap the "not connected" header text):
    // arm/disarm the synthetic demo state. No-op while a real machine is linked.
    fun toggleDemoMode() {
        if (client.uiState.value.phase == ConnectionPhase.Connected) return
        _demoMode.value = !_demoMode.value
    }

    // Offline dev shortcut #2 (triple-tap the disabled "Start shot" button):
    // queue a synthetic report so the standard "Show report?" prompt appears.
    // It then flows through the exact post-shot path — editable and auto-saved.
    fun injectDemoReport() {
        _pendingReport.value = DemoData.report()
    }

    fun connect() = client.start()
    fun disconnect() = client.disconnect()
    fun applySetpoint(celsius: Float) = client.writeSetpoint(celsius)
    fun applyThermoblockOffset(offsetC: Float) = client.writeThermoblockOffset(offsetC)
    fun applyThermoblockSetpoint(celsius: Float) = client.writeThermoblockSetpoint(celsius)
    fun applySettings(settings: MachineSettings) = client.writeSettings(settings)
    fun setBrewing(active: Boolean) = client.writeBrew(active)
    fun setHeaterEnabled(enabled: Boolean) = client.writeHeaterEnabled(enabled)
    fun applyPreinfusion(seconds: Float) = client.writePreinfusion(seconds)
    fun triggerFlush() = client.writeFlush(true)
    fun stopFlush() = client.writeFlush(false)

    fun upsertBean(bean: BeanEntity) = viewModelScope.launch { repo.upsert(bean) }
    fun deleteBean(bean: BeanEntity) = viewModelScope.launch { repo.delete(bean) }
    fun upsertGrinder(grinder: GrinderEntity) = viewModelScope.launch { repo.upsert(grinder) }
    fun deleteGrinder(grinder: GrinderEntity) = viewModelScope.launch { repo.delete(grinder) }
    fun upsertWater(water: WaterEntity) = viewModelScope.launch { repo.upsert(water) }
    fun deleteWater(water: WaterEntity) = viewModelScope.launch { repo.delete(water) }
    fun upsertRecipe(recipe: RecipeEntity) = viewModelScope.launch { repo.upsert(recipe) }
    fun deleteRecipe(recipe: RecipeEntity) = viewModelScope.launch { repo.delete(recipe) }

    // Persist a fresh ShotEntity for `report` (called on "Show report" so every
    // opened report ends up in the library). The generated row id is delivered
    // back to the caller so subsequent edits can update the same row.
    fun autoSaveShot(report: ShotReport, onSaved: (Long) -> Unit) =
        viewModelScope.launch {
            val id = repo.upsert(
                ShotEntity(
                    savedAtEpochMs = System.currentTimeMillis(),
                    durationMs = report.durationMs,
                    targetC = report.targetC,
                    preinfusionSec = report.preinfusionSec,
                    peakBar = report.peakBar,
                    avgBar = report.avgBar,
                    avgGroupC = report.avgGroupC.takeUnless { it.isNaN() },
                    peakGroupC = report.peakGroupC,
                    finalGroupC = report.finalGroupC,
                    samplesJson = ShotSamples.encode(report.samples),
                ),
            )
            onSaved(id)
        }

    // Full-row overwrite; the entity must carry the id of the shot to update.
    fun updateShot(shot: ShotEntity) = viewModelScope.launch { repo.upsert(shot) }

    fun deleteShotById(id: Long) = viewModelScope.launch {
        val current = shots.value.firstOrNull { it.id == id } ?: return@launch
        repo.delete(current)
    }

    fun deleteShot(shot: ShotEntity) = viewModelScope.launch { repo.delete(shot) }

    companion object {
        private const val POST_SHOT_HOLD_MS = 10_000L
        private const val REPORT_PAD_MS = 10_000L

        private fun buildReport(
            durationMs: Long,
            startUptime: Long,
            endUptime: Long,
            targetC: Float,
            preinfusionSec: Float,
            history: List<com.esp32esso.tier1.ble.GraphSample>,
        ): ShotReport {
            val from = startUptime - REPORT_PAD_MS
            val to = endUptime + REPORT_PAD_MS
            val slice = history.filter { it.uptimeMs in from..to }
            val pressures = slice.mapNotNull { if (it.pressureBar.isNaN()) null else it.pressureBar }
            val groupTemps = slice.mapNotNull { if (it.groupC.isNaN()) null else it.groupC }
            val peakBar = pressures.maxOrNull() ?: Float.NaN
            val avgBar = if (pressures.isEmpty()) Float.NaN else pressures.average().toFloat()
            val avgGroup = if (groupTemps.isEmpty()) Float.NaN else groupTemps.average().toFloat()
            val peakGroup = groupTemps.maxOrNull() ?: Float.NaN
            val finalGroup = groupTemps.lastOrNull() ?: Float.NaN
            return ShotReport(
                durationMs = durationMs,
                targetC = targetC,
                preinfusionSec = preinfusionSec,
                peakBar = peakBar,
                avgBar = avgBar,
                avgGroupC = avgGroup,
                finalGroupC = finalGroup,
                peakGroupC = peakGroup,
                samples = slice,
                shotStartUptimeMs = startUptime,
                shotEndUptimeMs = endUptime,
            )
        }
    }
}
