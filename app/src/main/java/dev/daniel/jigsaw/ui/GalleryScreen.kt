package dev.daniel.jigsaw.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.puzzle.SavedPuzzle

/** Main view: every image from every configured source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: Load<GalleryContent>,
    tiles: Map<String, SavedPuzzle>,
    settings: PuzzleSettings,
    hasSources: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onPick: (GalleryImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Jigsaw") },
                actions = {
                    if (hasSources) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                !hasSources -> Message(
                    "No image sources yet. Add a Nextcloud share, Immich shared link, or URL list in Settings.",
                    action = "Open settings",
                    onAction = onOpenSettings,
                )
                state is Load.Loading -> CircularProgressIndicator()
                state is Load.Failed -> Message("Couldn't list images:\n${state.message}", "Retry", onRefresh)
                state is Load.Ready && state.value.images.isEmpty() && state.value.failures.isEmpty() ->
                    Message("No images found in your sources.", "Refresh", onRefresh)
                state is Load.Ready -> ImageGrid(state.value, tiles, settings, onPick)
            }
        }
    }
}

@Composable
private fun ImageGrid(
    content: GalleryContent,
    tiles: Map<String, SavedPuzzle>,
    settings: PuzzleSettings,
    onPick: (GalleryImage) -> Unit,
) {
    val canvas = puzzleCanvasSpec()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (content.failures.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Some sources couldn't be loaded:\n" + content.failures.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        items(content.images, key = { it.key }) { item ->
            GalleryThumbnail(
                item,
                saved = tiles[item.key],
                canvas = canvas,
                settings = settings,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(item) },
            )
        }
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, textAlign = TextAlign.Center)
        Button(onClick = onAction) { Text(action) }
    }
}
