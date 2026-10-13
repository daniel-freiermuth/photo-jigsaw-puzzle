package dev.daniel.jigsaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.source.SourceConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: List<SourceConfig>,
    onAdd: (SourceConfig) -> String?,
    onRemove: (String) -> Unit,
    settings: PuzzleSettings,
    onSettingsChange: (PuzzleSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar("Settings", onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Image sources") }
            if (sources.isEmpty()) {
                item {
                    Text(
                        "Add a Nextcloud public share, an Immich shared link, or a URL list to start puzzling.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(sources, key = { it.id }) { source ->
                SourceRow(source, onRemove = { pendingDelete = source })
            }
            item {
                OutlinedButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add source", Modifier.padding(start = 8.dp))
                }
            }
            puzzleSettingsItems(settings, onSettingsChange)
        }
    }

    if (adding) {
        AddSourceDialog(
            onDismiss = { adding = false },
            onSubmit = { config -> onAdd(config).also { if (it == null) adding = false } },
        )
    }

    pendingDelete?.let { source ->
        RemoveSourceDialog(
            source = source,
            onConfirm = {
                onRemove(source.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun LazyListScope.puzzleSettingsItems(settings: PuzzleSettings, onChange: (PuzzleSettings) -> Unit) {
    item { SectionHeader("Puzzle", Modifier.padding(top = 16.dp)) }
    item {
        SwitchRow(
            title = "Snap pieces onto board",
            description = "Pieces dropped near their spot lock in place. Makes puzzles much easier.",
            checked = settings.snapToBoard,
            onCheckedChange = { onChange(settings.copy(snapToBoard = it)) },
        )
    }
    item {
        SwitchRow(
            title = "Rotate pieces",
            description = "New puzzles start with pieces turned. Tap a piece to turn it.",
            checked = settings.rotatePieces,
            onCheckedChange = { onChange(settings.copy(rotatePieces = it)) },
        )
    }
}

@Composable
private fun RemoveSourceDialog(
    source: SourceConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Remove “${source.label}”?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SourceRow(source: SourceConfig, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${source.kind.title} · ${source.url}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

private enum class SourceKind(val title: String, val hint: String) {
    Nextcloud("Nextcloud", "https://cloud.example.com/s/AbCdEf123"),
    Immich("Immich", "https://photos.example.com/share/…"),
    UrlList("URL list", "https://example.com/puzzles.txt"),
}

private val SourceConfig.kind
    get() = when (this) {
        is SourceConfig.NextcloudShare -> SourceKind.Nextcloud
        is SourceConfig.ImmichShare -> SourceKind.Immich
        is SourceConfig.UrlList -> SourceKind.UrlList
    }

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onSubmit: (SourceConfig) -> String?, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(SourceKind.Nextcloud) }
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Add image source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title) })
                    }
                }
                OutlinedTextField(
                    url,
                    { input ->
                        url = input
                        error = null
                    },
                    label = { Text("Share link") },
                    placeholder = { Text(kind.hint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true)
                if (kind == SourceKind.Nextcloud) {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Share password (if any)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSubmit(newSourceConfig(kind, label, url, password)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun newSourceConfig(kind: SourceKind, label: String, url: String, password: String): SourceConfig {
    val trimmed = url.trim()
    val name = label.trim().ifEmpty { trimmed.toHttpUrlOrNull()?.host ?: trimmed }
    val id = UUID.randomUUID().toString()
    return when (kind) {
        SourceKind.Nextcloud -> SourceConfig.NextcloudShare(id, name, trimmed, password)
        SourceKind.Immich -> SourceConfig.ImmichShare(id, name, trimmed)
        SourceKind.UrlList -> SourceConfig.UrlList(id, name, trimmed)
    }
}
