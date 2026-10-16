package dev.daniel.jigsaw

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.daniel.jigsaw.ui.AppViewModel
import dev.daniel.jigsaw.ui.GalleryScreen
import dev.daniel.jigsaw.ui.PuzzleScreen
import dev.daniel.jigsaw.ui.Screen
import dev.daniel.jigsaw.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val language = AppLocale.current(this)
        setContent { AppTheme { App(language, onLanguageChange = { AppLocale.set(this, it) }, vm = vm) } }
    }
}

// Not private: called from the Activity's lambda, which would otherwise need a synthetic accessor.
@Composable
internal fun App(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    vm: AppViewModel,
) {
    // Hoisted above the screen switch so the gallery's scroll position survives visiting other screens.
    val galleryGrid = rememberLazyGridState()
    BackHandler(enabled = vm.screen != Screen.Gallery) { vm.back() }
    when (val screen = vm.screen) {
        Screen.Gallery -> {
            // Keeps the gallery current exactly while it is visible: on screen, app in the foreground.
            val lifecycle = LocalLifecycleOwner.current
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.feed.keepFresh() }
            }
            GalleryScreen(
                state = vm.feed.state,
                tiles = vm.tiles,
                settings = vm.settings,
                gridState = galleryGrid,
                refreshing = vm.feed.refreshing,
                hasSources = vm.sources.isNotEmpty(),
                onRefresh = vm.feed::refresh,
                onOpenSettings = vm::openSettings,
                onPick = vm::openImage,
            )
        }

        Screen.Settings -> SettingsScreen(
            sources = vm.sources,
            onAdd = vm::addSource,
            onRemove = vm::removeSource,
            settings = vm.settings,
            onSettingsChange = vm::updateSettings,
            language = language,
            onLanguageChange = onLanguageChange,
            onBack = vm::back,
        )

        is Screen.Puzzle -> PuzzleScreen(
            title = screen.image.image.name,
            state = vm.puzzle,
            onBack = vm::back,
            onAnotherImage = vm::openRandomImage,
            onStartOver = vm::restartPuzzle,
            onMoveEnd = vm::saveCurrentPuzzle,
            snapToBoard = vm.settings.snapToBoard,
        )
    }
}

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()

        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
