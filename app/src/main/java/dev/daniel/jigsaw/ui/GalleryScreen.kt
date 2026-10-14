package dev.daniel.jigsaw.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.R
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.puzzle.SavedPuzzle

/** Main view: every image from every configured source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: Load<GalleryContent>,
    tiles: Map<String, SavedPuzzle>,
    settings: PuzzleSettings,
    refreshing: Boolean,
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (hasSources) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        // While refreshing, the current images stay; the pull indicator shows that a refresh is running.
        PullToRefreshBox(
            isRefreshing = refreshing && state is Load.Ready,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasSources -> Message(
                    stringResource(R.string.gallery_no_sources),
                    action = stringResource(R.string.gallery_open_settings),
                    onAction = onOpenSettings,
                )
                state is Load.Ready -> GalleryContentView(state.value, tiles, settings, onRefresh, onPick)
                else -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun GalleryContentView(
    content: GalleryContent,
    tiles: Map<String, SavedPuzzle>,
    settings: PuzzleSettings,
    onRefresh: () -> Unit,
    onPick: (GalleryImage) -> Unit,
) {
    when {
        content.images.isEmpty() && content.failures.isNotEmpty() -> Message(
            stringResource(R.string.gallery_list_failed) + "\n" + failureLines(content.failures),
            stringResource(R.string.gallery_retry),
            onRefresh,
        )
        content.images.isEmpty() ->
            Message(stringResource(R.string.gallery_empty), stringResource(R.string.action_refresh), onRefresh)
        else -> ImageGrid(content, tiles, settings, onPick)
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
                        stringResource(R.string.gallery_some_failed) + "\n" + failureLines(content.failures),
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

/** One "source: reason" line per failed source. */
@Composable
private fun failureLines(failures: List<SourceFailure>): String =
    failures.map { stringResource(R.string.gallery_source_failure, it.label, it.reason.resolve()) }.joinToString("\n")
