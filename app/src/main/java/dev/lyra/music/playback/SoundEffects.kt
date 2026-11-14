package dev.lyra.music.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.lyra.music.data.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** Everything the sound editor controls, in real units. */
data class SoundSettings(
    val eqEnabled: Boolean = false,
    /** Ten band gains in dB. */
    val bandsDb: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    /** -1 (left only) … 0 … +1 (right only). */
    val balance: Float = 0f,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
) {
    /** True when anything alters the sound; false means the signal path is untouched. */
    val active: Boolean get() = eqEnabled || balance != 0f || speed != 1f || pitchSemitones != 0f

    companion object {
        val BAND_CENTERS_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        /** Upper edge of each band: geometric mean with the next centre; the last band runs to 20 kHz. */
        val BAND_CUTOFFS_HZ: FloatArray = FloatArray(10) { i ->
            if (i < 9) kotlin.math.sqrt(BAND_CENTERS_HZ[i] * BAND_CENTERS_HZ[i + 1]) else 20000f
        }

        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
            "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f),
            "Classical" to listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 2f, 3f),
            "Jazz" to listOf(2f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
            "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
            "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
            "Electronic" to listOf(4f, 3f, 1f, 0f, -2f, 1f, 0f, 1f, 3f, 4f),
        )

        fun fromPrefs(prefs: UserPrefs) = combine(
            combine(prefs.eqEnabled, prefs.eqBands, prefs.eqPreampTenths) { on, bands, pre -> Triple(on, bands, pre) },
            prefs.eqBalance, prefs.playSpeedPct, prefs.playPitchSteps,
        ) { eq, bal, speed, pitch ->
            SoundSettings(
                eqEnabled = eq.first, bandsDb = eq.second.map { it / 10f }, preampDb = eq.third / 10f,
                balance = bal / 100f, speed = speed / 100f, pitchSemitones = pitch / 2f,
            )
        }.distinctUntilChanged()
    }
}

/**
 * Applies the sound editor to the player.
 *
 * Audio-quality policy: everything here is off by default, and when off no effect object exists
 * on the audio session at all. The equaliser and balance use the platform's DynamicsProcessing
 * effect (falling back to the classic Equalizer), which runs in the system mixer – the app's
 * own float PCM output stays untouched. Speed and pitch use the audio track's native playback
 * parameters for the same reason.
 */
class SoundEffects(
    private val player: ExoPlayer,
    private val prefs: UserPrefs,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var settings = SoundSettings()

    fun start() {
        sessionId = player.audioSessionId
        player.addListener(this)
        scope.launch { SoundSettings.fromPrefs(prefs).collect { settings = it; apply() } }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId == sessionId) return
        releaseEffects()
        sessionId = audioSessionId
        apply()
    }

    fun release() {
        player.removeListener(this)
        releaseEffects()
    }

    private fun releaseEffects() {
        runCatching { dynamics?.release() }; dynamics = null
        runCatching { equalizer?.release() }; equalizer = null
    }

    private fun apply() {
        val s = settings
        // Speed / pitch.
        val params = PlaybackParameters(s.speed.coerceIn(0.5f, 2f), 2f.pow(s.pitchSemitones / 12f))
        if (player.playbackParameters != params) player.playbackParameters = params

        val needEffect = s.eqEnabled || s.balance != 0f
        if (!needEffect || sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) {
            releaseEffects()
            return
        }
        if (dynamics == null && equalizer == null) create()
        dynamics?.let { runCatching { configure(it, s) } }
        equalizer?.let { runCatching { configure(it, s) } }
    }

    private fun create() {
        dynamics = runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true, /* preEqBandCount = */ 10,
                /* mbcInUse = */ false, /* mbcBandCount = */ 0,
                /* postEqInUse = */ false, /* postEqBandCount = */ 0,
                /* limiterInUse = */ true,
            ).build()
            DynamicsProcessing(0, sessionId, cfg)
        }.getOrNull()
        if (dynamics == null) equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
    }

    private fun configure(dp: DynamicsProcessing, s: SoundSettings) {
        for (i in 0 until 10) {
            val gain = if (s.eqEnabled) s.bandsDb[i].coerceIn(-12f, 12f) else 0f
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, SoundSettings.BAND_CUTOFFS_HZ[i], gain))
        }
        val pre = if (s.eqEnabled) s.preampDb.coerceIn(-12f, 12f) else 0f
        // Balance: attenuate the opposite side.
        val left = pre + attenuationDb(if (s.balance > 0) s.balance else 0f)
        val right = pre + attenuationDb(if (s.balance < 0) -s.balance else 0f)
        runCatching { dp.setInputGainbyChannel(0, left) }
        runCatching { dp.setInputGainbyChannel(1, right) }
        // A gentle limiter so boosted bands cannot clip.
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp.enabled = true
    }

    private fun attenuationDb(amount: Float): Float {
        if (amount <= 0f) return 0f
        val remaining = 1f - amount
        return if (remaining <= 0.0001f) -80f else (20f * log10(remaining)).coerceAtLeast(-80f)
    }

    /** Classic Equalizer fallback: device bands take the gain of the nearest of our ten bands. */
    private fun configure(eq: Equalizer, s: SoundSettings) {
        val range = eq.bandLevelRange
        for (b in 0 until eq.numberOfBands) {
            val centerHz = eq.getCenterFreq(b.toShort()) / 1000f
            val nearest = SoundSettings.BAND_CENTERS_HZ.indices.minByOrNull { abs(ln(SoundSettings.BAND_CENTERS_HZ[it] / centerHz)) } ?: 0
            val gainDb = if (s.eqEnabled) s.bandsDb[nearest] + s.preampDb else 0f
            val level = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(b.toShort(), level.toShort())
        }
        eq.enabled = s.eqEnabled
    }
}
