package dev.daniel.jigsaw.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.daniel.jigsaw.AppLanguage
import dev.daniel.jigsaw.R
import dev.daniel.jigsaw.puzzle.JigsawCut
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.source.SourceConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: List<SourceConfig>,
    onAdd: (SourceConfig) -> Boolean,
    onRemove: (String) -> Unit,
    settings: PuzzleSettings,
    onSettingsChange: (PuzzleSettings) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(stringResource(R.string.action_settings), onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader(stringResource(R.string.settings_sources)) }
            if (sources.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_no_sources),
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
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Text(stringResource(R.string.settings_add_source), Modifier.padding(start = 8.dp))
                }
            }
            puzzleSettingsItems(settings, onSettingsChange)
            item { SectionHeader(stringResource(R.string.settings_language), Modifier.padding(top = 16.dp)) }
            item { LanguageRow(language, onLanguageChange) }
        }
    }

    if (adding) {
        AddSourceDialog(
            onDismiss = { adding = false },
            onSubmit = { config -> onAdd(config).also { if (it) adding = false } },
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
    item { SectionHeader(stringResource(R.string.settings_puzzle), Modifier.padding(top = 16.dp)) }
    item { PieceCountRow(settings.pieces, onSelect = { onChange(settings.copy(pieces = it)) }) }
    item {
        SwitchRow(
            title = stringResource(R.string.settings_snap),
            description = stringResource(R.string.settings_snap_description),
            checked = settings.snapToBoard,
            onCheckedChange = { onChange(settings.copy(snapToBoard = it)) },
        )
    }
    item {
        SwitchRow(
            title = stringResource(R.string.settings_rotate),
            description = stringResource(R.string.settings_rotate_description),
            checked = settings.rotatePieces,
            onCheckedChange = { onChange(settings.copy(rotatePieces = it)) },
        )
    }
}

@Composable
private fun PieceCountRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_pieces), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_pieces_description),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JigsawCut.PIECE_COUNTS.forEach { count ->
                FilterChip(
                    selected = count == selected,
                    onClick = { onSelect(count) },
                    label = { Text(count.toString()) },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppLanguage.entries.forEach { language ->
            FilterChip(
                selected = language == selected,
                onClick = { if (language != selected) onSelect(language) },
                label = { Text(stringResource(language.label), maxLines = 1, softWrap = false) },
            )
        }
    }
}

/** System default in the current language; each translation by its own name, so it can be found when unreadable. */
private val AppLanguage.label
    @StringRes get() = when (this) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.English -> R.string.language_english
        AppLanguage.German -> R.string.language_german
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
        title = { Text(stringResource(R.string.settings_remove_title, source.label)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
                    sourceLinkText(source),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.settings_remove),
                )
            }
        }
    }
}

/** "Kind · url" with the URL as a link that opens the share in the browser. */
@Composable
private fun sourceLinkText(source: SourceConfig): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val kind = stringResource(source.kind.title)
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        append("$kind · ")
        val link = LinkAnnotation.Url(source.url, linkStyle) {
            try {
                uriHandler.openUri(source.url)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.settings_link_no_app, Toast.LENGTH_SHORT).show()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, R.string.settings_link_invalid, Toast.LENGTH_SHORT).show()
            }
        }
        withLink(link) { append(source.url) }
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

private enum class SourceKind(@param:StringRes val title: Int, @param:StringRes val invalid: Int, val hint: String) {
    Nextcloud(
        R.string.source_kind_nextcloud,
        R.string.source_invalid_nextcloud,
        "https://cloud.example.com/s/AbCdEf123",
    ),
    Immich(R.string.source_kind_immich, R.string.source_invalid_immich, "https://photos.example.com/share/…"),
    UrlList(R.string.source_kind_url_list, R.string.source_invalid_url_list, "https://example.com/puzzles.txt"),
}

private val SourceConfig.kind
    get() = when (this) {
        is SourceConfig.NextcloudShare -> SourceKind.Nextcloud
        is SourceConfig.ImmichShare -> SourceKind.Immich
        is SourceConfig.UrlList -> SourceKind.UrlList
    }

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onSubmit: (SourceConfig) -> Boolean, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(SourceKind.Nextcloud) }
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.source_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindChips(
                    kind,
                    onSelect = {
                        kind = it
                        invalid = false
                    },
                )
                OutlinedTextField(
                    url,
                    { input ->
                        url = input
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.source_link)) },
                    placeholder = { Text(kind.hint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text(stringResource(R.string.source_name)) },
                    singleLine = true,
                )
                if (kind == SourceKind.Nextcloud) {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text(stringResource(R.string.source_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                if (invalid) Text(stringResource(kind.invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onSubmit(newSourceConfig(kind, label, url, password)) }) {
                Text(stringResource(R.string.source_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun KindChips(selected: SourceKind, onSelect: (SourceKind) -> Unit, modifier: Modifier = Modifier) {
    // Wraps rather than squeezing labels when a translation makes the chips wider than the dialog.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SourceKind.entries.forEach { k ->
            FilterChip(
                selected = selected == k,
                onClick = { onSelect(k) },
                label = { Text(stringResource(k.title), maxLines = 1, softWrap = false) },
            )
        }
    }
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
