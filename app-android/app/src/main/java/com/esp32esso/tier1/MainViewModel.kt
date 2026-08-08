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
    private val profilePrefs =
        application.getSharedPreferences(PROFILE_PREFS, Application.MODE_PRIVATE)

    // The currently selected brew profile id (null = "None"). Persisted locally
    // so it survives app restarts, and re-sent to the firmware on every
    // (re)connect so the two stay in sync even though the firmware also keeps
    // its own NVS copy for switch-triggered shots.
    private val _selectedProfileId =
        MutableStateFlow(profilePrefs.getString(PROFILE_KEY, null))
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    // Offline developer shortcut: when on (and no real machine is linked) the
    // whole UI runs off DemoData's synthetic connected state.
    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    // True while the currently applied thermoblock offset is one the app
    // calculated (on a group-target apply) rather than one the operator dialled
    // or picked from a preset. Drives the offset card's "AUTO" tag / preset
    // deselection. Any manual offset or block override flips it back to false.
    private val _offsetAuto = MutableStateFlow(false)
    val offsetAuto: StateFlow<Boolean> = _offsetAuto.asStateFlow()

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

    // Survives dismissing the "Show brew report?" prompt (unlike pendingReport,
    // which is cleared either way) so the operator can still get to it from the
    // "Show last brew report" button on the ranking page if they regret not
    // opening it right away. lastReportShotId tracks whether it has since been
    // saved: null means "not yet in the library", so the button stays useful;
    // once it's saved (either by opening the prompt normally or via that
    // button, see markLastReportSaved) the shot already has its own card in
    // the recent-brews list, so the button hides itself again.
    private val _lastReport = MutableStateFlow<ShotReport?>(null)
    val lastReport: StateFlow<ShotReport?> = _lastReport.asStateFlow()
    private val _lastReportShotId = MutableStateFlow<Long?>(null)
    val lastReportShotId: StateFlow<Long?> = _lastReportShotId.asStateFlow()

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

    // Push the selected brew profile once per link, after bring-up is idle.
    // Writing earlier races Android's single in-flight GATT op against MTU /
    // CCCD / the config read chain: that used to drop notify enable (Connected
    // with no telemetry), and even after that fix a mid-chain write returns
    // false and the profile is lost. pumpRampSec is the last read in the chain,
    // so once it and MachineState are both set the slot is free.
    private var profilePushedThisLink = false

    init {
        viewModelScope.launch {
            client.uiState.collect { state ->
                val machine = state.machineState
                if (machine != null) trackBrew(machine)
                if (state.phase != ConnectionPhase.Connected) {
                    profilePushedThisLink = false
                    return@collect
                }
                if (machine != null && state.pumpRampSec != null && !profilePushedThisLink) {
                    profilePushedThisLink = true
                    client.writeBrewProfile(BrewProfileLibrary.byId(_selectedProfileId.value))
                }
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
            val thermoblockOffset = machine.thermoblockOffsetC
            val thermoblockManual = machine.thermoblockManual
            postShotJob =
                viewModelScope.launch {
                    delay(POST_SHOT_HOLD_MS)
                    _displayShotTimeMs.value = 0L
                    // Very short "shots" are almost always a mis-tap or an
                    // aborted start rather than a real pull worth a report, so
                    // skip building one entirely -- this is also what keeps
                    // the "Show brew report?" prompt from nagging the operator
                    // after those.
                    if (heldTime > MIN_REPORT_DURATION_MS) {
                        val built =
                            buildReport(
                                durationMs = heldTime,
                                startUptime = startUptime,
                                endUptime = endUptime,
                                targetC = target,
                                preinfusionSec = preinfusion,
                                thermoblockOffsetC = thermoblockOffset,
                                thermoblockManual = thermoblockManual,
                                history = client.uiState.value.history,
                            )
                        _pendingReport.value = built
                        _lastReport.value = built
                        _lastReportShotId.value = null
                    }
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

    // Records that the last report now has a row of its own in the library,
    // so the "Show last brew report" shortcut hides itself again.
    fun markLastReportSaved(id: Long) {
        _lastReportShotId.value = id
    }

    // Offline dev shortcut #1 (triple-tap the "not connected" header text):
    // arm/disarm the synthetic demo state. No-op while a real machine is linked.
    fun toggleDemoMode() {
        if (client.uiState.value.phase == ConnectionPhase.Connected) return
        _demoMode.value = !_demoMode.value
    }

    // Offline dev shortcut #2 (triple-tap the disabled "Start brew" button):
    // queue a synthetic report so the standard "Show report?" prompt appears.
    // It then flows through the exact post-shot path — editable and auto-saved.
    fun injectDemoReport() {
        val report = DemoData.report()
        _pendingReport.value = report
        _lastReport.value = report
        _lastReportShotId.value = null
    }

    fun connect() = client.start()
    fun disconnect() = client.disconnect()

    // Setting the group target is an explicit override and must always win,
    // clearing any manual thermoblock override that might still be latched —
    // previously that clear only happened when calibration history existed,
    // so with no usable shots yet the block (and the group readout, which
    // reads off the block while manual is active) stayed stuck on the old
    // manual figure and the group tap appeared to do nothing. Writing the
    // offset unconditionally (recalculated when there's history, otherwise
    // just re-asserting the current offset) always drops the manual flag, so
    // the group setpoint applied right after always takes effect.
    fun applySetpoint(celsius: Float) {
        val calculated = calculatedThermoblockOffsetC()
        val offsetToApply = calculated ?: client.uiState.value.machineState?.thermoblockOffsetC ?: 0f
        client.writeThermoblockOffset(offsetToApply)
        _offsetAuto.value = calculated != null
        client.writeSetpoint(celsius)
    }

    // Operator-driven offset (slider or preset tap): no longer the auto value.
    fun applyThermoblockOffset(offsetC: Float) {
        _offsetAuto.value = false
        client.writeThermoblockOffset(offsetC)
    }

    // Manual absolute block override also leaves the auto-offset regime.
    fun applyThermoblockSetpoint(celsius: Float) {
        _offsetAuto.value = false
        client.writeThermoblockSetpoint(celsius)
    }
    // Fixed brew pressure. A positive value holds that pressure for the whole
    // shot and outranks the selected profile; 0 hands the shot back to it.
    fun applyPressureSetpoint(bar: Float) = client.writePressureSetpoint(bar)
    fun applySettings(settings: MachineSettings) = client.writeSettings(settings)
    // Plain pull: pre-infusion + ramp, ignoring any selected profile.
    fun setBrewing(active: Boolean) = client.writeBrew(active, useProfile = false)

    // Profile pull: runs the selected profile's phase program.
    fun setProfileBrewing(active: Boolean) = client.writeBrew(active, useProfile = true)
    fun setHeaterEnabled(enabled: Boolean) = client.writeHeaterEnabled(enabled)
    fun applyPreinfusion(seconds: Float) = client.writePreinfusion(seconds)
    fun applyPumpRamp(seconds: Float) = client.writePumpRamp(seconds)

    // Select (or clear, with null) the bundled pressure/flow profile. Persists
    // the choice locally and pushes the phase program to the firmware.
    fun applyBrewProfile(profile: BrewProfile?) {
        _selectedProfileId.value = profile?.id
        profilePrefs.edit().apply {
            if (profile == null) remove(PROFILE_KEY) else putString(PROFILE_KEY, profile.id)
        }.apply()
        client.writeBrewProfile(profile)
    }
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
                    thermoblockOffsetC = report.thermoblockOffsetC.takeUnless { it.isNaN() },
                    thermoblockManual = report.thermoblockManual,
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

    // Average, over the last OFFSET_HISTORY_SAMPLES relationally-brewed shots,
    // the offset correction that would have hit each shot's group target
    // exactly: offsetUsed - (achievedGroup - target). Manual-override shots are
    // skipped (their offset wasn't driving the thermoblock) and so are shots
    // shorter than MIN_CALIBRATION_SHOT_MS, whose brief group readings never
    // reach a representative steady state. Recomputed straight off the `shots`
    // flow so it tracks the latest brews — and falls back to the saved recent
    // brews until 10 fresh ones exist. Null when there isn't a usable shot yet,
    // so callers can leave the current offset alone.
    private fun calculatedThermoblockOffsetC(): Float? {
        val corrections =
            shots.value
                .asSequence()
                .filter { !it.thermoblockManual }
                .filter { it.durationMs > MIN_CALIBRATION_SHOT_MS }
                .mapNotNull { shot ->
                    val offsetUsed = shot.thermoblockOffsetC ?: return@mapNotNull null
                    val achievedGroupC = shot.avgGroupC ?: return@mapNotNull null
                    offsetUsed - (achievedGroupC - shot.targetC)
                }
                .take(OFFSET_HISTORY_SAMPLES)
                .toList()
        return corrections.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }

    companion object {
        private const val POST_SHOT_HOLD_MS = 10_000L
        private const val MIN_REPORT_DURATION_MS = 10_000L
        private const val REPORT_PAD_MS = 10_000L
        private const val OFFSET_HISTORY_SAMPLES = 10
        private const val MIN_CALIBRATION_SHOT_MS = 20_000L
        private const val PROFILE_PREFS = "esso_brew_profile"
        private const val PROFILE_KEY = "selected_id"

        private fun buildReport(
            durationMs: Long,
            startUptime: Long,
            endUptime: Long,
            targetC: Float,
            preinfusionSec: Float,
            thermoblockOffsetC: Float,
            thermoblockManual: Boolean,
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
                thermoblockOffsetC = thermoblockOffsetC,
                thermoblockManual = thermoblockManual,
                samples = slice,
                shotStartUptimeMs = startUptime,
                shotEndUptimeMs = endUptime,
            )
        }
    }
}
