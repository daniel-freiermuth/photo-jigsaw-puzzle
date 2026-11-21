package dev.daniel.jigsaw

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import dev.daniel.jigsaw.puzzle.PieceCount
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.source.SourceConfig
import dev.daniel.jigsaw.source.StoredSources
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.InputStream
import java.io.OutputStream

/** What the user set up in Settings: the image sources and the puzzle preferences. */
data class AppConfig(
    val sources: List<SourceConfig> = emptyList(),
    val settings: PuzzleSettings = PuzzleSettings(),
)

/**
 * The app's one [AppConfig] store, `files/datastore/config.json`. DataStore allows a single instance per
 * file, hence this process-wide delegate. Created on first use, which moves over what versions before it
 * kept in SharedPreferences ([appConfigMigrations]).
 */
val Context.appConfig: DataStore<AppConfig> by dataStore(
    fileName = "config.json",
    serializer = AppConfigSerializer,
    produceMigrations = ::appConfigMigrations,
)

/**
 * Moves the sources and puzzle settings out of the SharedPreferences files `sources` and `settings`, where
 * versions before DataStore kept them. DataStore writes the result before it deletes the preferences, so
 * the move is never half done: if the app dies in between, it is simply repeated on the next start.
 */
internal fun appConfigMigrations(context: Context): List<DataMigration<AppConfig>> = listOf(
    SharedPreferencesMigration(context, LegacyPreferences.SOURCES) { prefs, config ->
        prefs.getString(LegacyPreferences.KEY_SOURCES)?.let { config.copy(sources = StoredSources.decode(it)) }
            ?: config
    },
    SharedPreferencesMigration(context, LegacyPreferences.SETTINGS) { prefs, config ->
        val defaults = PuzzleSettings()
        config.copy(
            settings = PuzzleSettings(
                snapToBoard = prefs.getBoolean(LegacyPreferences.KEY_SNAP_TO_BOARD, defaults.snapToBoard),
                rotatePieces = prefs.getBoolean(LegacyPreferences.KEY_ROTATE_PIECES, defaults.rotatePieces),
                pieces = PieceCount.fromTarget(prefs.getInt(LegacyPreferences.KEY_PIECES, defaults.pieces.target)),
            ),
        )
    },
)

/** Names of the SharedPreferences files and keys versions before DataStore wrote. */
internal object LegacyPreferences {
    const val SOURCES = "sources"
    const val KEY_SOURCES = "sources"
    const val SETTINGS = "settings"
    const val KEY_SNAP_TO_BOARD = "snapToBoard"
    const val KEY_ROTATE_PIECES = "rotatePieces"
    const val KEY_PIECES = "pieces"
}

/**
 * [AppConfig] as JSON. Read one value at a time: an unknown or broken source entry costs only that source
 * ([StoredSources.decode]) and a value of the wrong type only that setting, so a bad setting never takes the
 * sources (and with them their puzzle saves) down. A file that isn't a JSON object counts as nothing
 * configured, as an unreadable preferences file did, so it never crashes the app on start, and the next save
 * replaces it.
 */
internal object AppConfigSerializer : Serializer<AppConfig> {
    private const val TAG = "AppConfigSerializer"

    override val defaultValue = AppConfig()

    override suspend fun readFrom(input: InputStream): AppConfig = decode(input.readBytes().decodeToString())

    override suspend fun writeTo(t: AppConfig, output: OutputStream) {
        output.write(encode(t).encodeToByteArray())
    }

    fun encode(config: AppConfig): String = WireJson.encodeToString(
        ConfigFile(
            sources = StoredSources.encode(config.sources),
            snapToBoard = config.settings.snapToBoard,
            rotatePieces = config.settings.rotatePieces,
            pieces = config.settings.pieces.target,
        ),
    )

    fun decode(json: String): AppConfig {
        val file = try {
            WireJson.decodeFromString<JsonObject>(json)
        } catch (e: SerializationException) {
            Log.w(TAG, "Stored config is unreadable, starting with none", e)
            return defaultValue
        }
        val defaults = ConfigFile()
        return AppConfig(
            sources = StoredSources.decode(file["sources"] as? JsonArray ?: defaults.sources),
            settings = PuzzleSettings(
                snapToBoard = file.literal("snapToBoard")?.booleanOrNull ?: defaults.snapToBoard,
                rotatePieces = file.literal("rotatePieces")?.booleanOrNull ?: defaults.rotatePieces,
                pieces = PieceCount.fromTarget(file.literal("pieces")?.intOrNull ?: defaults.pieces),
            ),
        )
    }

    /**
     * The unquoted value under [key] (a number, `true`/`false` or `null`), or null when it is missing or of
     * another type; the caller then uses the default.
     */
    private fun JsonObject.literal(key: String): JsonPrimitive? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }

    /** The file format, as written. Unknown keys are skipped, so add a key rather than change one's type. */
    @Serializable
    private class ConfigFile(
        @EncodeDefault val sources: List<JsonElement> = emptyList(),
        @EncodeDefault val snapToBoard: Boolean = false,
        @EncodeDefault val rotatePieces: Boolean = false,
        /** [PieceCount.target]; counts this version doesn't offer read as the default. */
        @EncodeDefault val pieces: Int = PieceCount.DEFAULT.target,
    )
}
