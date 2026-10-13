package dev.daniel.jigsaw

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.daniel.jigsaw.ui.AppViewModel
import dev.daniel.jigsaw.ui.GalleryScreen
import dev.daniel.jigsaw.ui.PuzzleScreen
import dev.daniel.jigsaw.ui.Screen
import dev.daniel.jigsaw.ui.SourcesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.screen != Screen.Sources) { vm.back() }
    when (val screen = vm.screen) {
        Screen.Sources -> SourcesScreen(
            sources = vm.sources,
            onOpen = vm::openSource,
            onAdd = vm::addSource,
            onRemove = vm::removeSource,
        )
        is Screen.Gallery -> GalleryScreen(
            title = screen.source.label,
            state = vm.gallery,
            onBack = vm::back,
            onRefresh = vm::refreshGallery,
            onPick = vm::openImage,
        )
        is Screen.Puzzle -> PuzzleScreen(
            title = screen.image.name,
            state = vm.puzzle,
            onBack = vm::back,
            onAnotherImage = vm::openRandomImage,
            snapToBoard = vm.snapToBoard,
            onSnapToBoardChange = vm::updateSnapToBoard,
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
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
