package dev.lyra.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.ui.nav.LyraRoot
import dev.lyra.music.ui.theme.LyraTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPrefs
    @Inject lateinit var floatingLyrics: dev.lyra.music.lyrics.FloatingLyricsController

    /** Incremented whenever an "open player" intent arrives so the UI can react. */
    private var openPlayerSignal by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        floatingLyrics.start()
        handleIntent(intent)
        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = 0)
            val dynamic by prefs.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            LyraTheme(themeMode = themeMode, dynamicColor = dynamic) {
                LyraRoot(openPlayerSignal = openPlayerSignal)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayerSignal++
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "dev.lyra.music.OPEN_PLAYER"
    }
}
