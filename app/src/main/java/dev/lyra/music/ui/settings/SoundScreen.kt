package dev.lyra.music.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyra.music.R
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.playback.SoundSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SoundViewModel @Inject constructor(private val prefs: UserPrefs) : ViewModel() {
    val settings = SoundSettings.fromPrefs(prefs).stateIn(viewModelScope, SharingStarted.Eagerly, SoundSettings())

    fun setEqEnabled(v: Boolean) = viewModelScope.launch { prefs.setEqEnabled(v) }
    fun setBand(index: Int, db: Float) = viewModelScope.launch {
        val bands = settings.value.bandsDb.map { (it * 10).roundToInt() }.toMutableList()
        bands[index] = (db * 10).roundToInt()
        prefs.setEqBands(bands)
    }
    fun setBands(db: List<Float>) = viewModelScope.launch { prefs.setEqBands(db.map { (it * 10).roundToInt() }) }
    fun setPreamp(db: Float) = viewModelScope.launch { prefs.setEqPreampTenths((db * 10).roundToInt()) }
    fun setBalance(v: Float) = viewModelScope.launch { prefs.setEqBalance((v * 100).roundToInt()) }
    fun setSpeed(v: Float) = viewModelScope.launch { prefs.setPlaySpeedPct((v * 100).roundToInt()) }
    fun setPitch(semitones: Float) = viewModelScope.launch { prefs.setPlayPitchSteps((semitones * 2).roundToInt()) }
}

/** Sound editor: equaliser, speed & pitch, balance – one place, three tabs. */
@Composable
fun SoundScreen(bottomPadding: PaddingValues, onBack: () -> Unit, vm: SoundViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(R.string.sound_tab_eq, R.string.sound_tab_speed, R.string.sound_tab_channel)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.sound_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
                )
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
                }
            }
        }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = inner.calculateTopPadding(), bottom = bottomPadding.calculateBottomPadding())
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> EqTab(s, vm)
                1 -> SpeedTab(s, vm)
                else -> BalanceTab(s, vm)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.sound_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun db(v: Float) = String.format(Locale.US, "%+.1f dB", v)

@Composable
private fun EqTab(s: SoundSettings, vm: SoundViewModel) {
    Row(Modifier.fillMaxWidth().clickable { vm.setEqEnabled(!s.eqEnabled) }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sound_eq_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = s.eqEnabled, onCheckedChange = { vm.setEqEnabled(it) })
    }
    Text(stringResource(R.string.sound_preset), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SoundSettings.PRESETS.forEach { (name, bands) ->
            FilterChip(selected = s.bandsDb == bands, onClick = { vm.setBands(bands); if (!s.eqEnabled && name != "Flat") vm.setEqEnabled(true) }, label = { Text(name) })
        }
    }
    Spacer(Modifier.height(8.dp))
    SoundSettings.BAND_CENTERS_HZ.forEachIndexed { i, hz ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString(), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = s.bandsDb[i], onValueChange = { vm.setBand(i, (it * 2).roundToInt() / 2f) },
                valueRange = -12f..12f, enabled = s.eqEnabled, modifier = Modifier.weight(1f)
            )
            Text(db(s.bandsDb[i]), Modifier.width(68.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sound_preamp), Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = s.preampDb, onValueChange = { vm.setPreamp((it * 2).roundToInt() / 2f) }, valueRange = -12f..12f, enabled = s.eqEnabled, modifier = Modifier.weight(1f))
        Text(db(s.preampDb), Modifier.width(68.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SpeedTab(s: SoundSettings, vm: SoundViewModel) {
    Text(stringResource(R.string.sound_speed) + "  " + String.format(Locale.US, "%.2f×", s.speed), style = MaterialTheme.typography.titleMedium)
    Slider(value = s.speed, onValueChange = { vm.setSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.sound_pitch) + "  " + String.format(Locale.US, "%+.1f", s.pitchSemitones), style = MaterialTheme.typography.titleMedium)
    Slider(value = s.pitchSemitones, onValueChange = { vm.setPitch((it * 2).roundToInt() / 2f) }, valueRange = -12f..12f)
    TextButton(onClick = { vm.setSpeed(1f); vm.setPitch(0f) }) { Text(stringResource(R.string.sound_reset)) }
}

@Composable
private fun BalanceTab(s: SoundSettings, vm: SoundViewModel) {
    Text(stringResource(R.string.sound_balance), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sound_left))
        Slider(value = s.balance, onValueChange = { vm.setBalance(if (kotlin.math.abs(it) < 0.04f) 0f else it) }, valueRange = -1f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(stringResource(R.string.sound_right))
    }
    TextButton(onClick = { vm.setBalance(0f) }) { Text(stringResource(R.string.sound_reset)) }
}
