package org.octopusden.octopus.components.registry.cli.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import org.octopusden.octopus.components.registry.cli.client.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A single named target profile. Only [crsUrl] is required to make a profile usable for read
 * commands; [keycloakIssuer] / [clientId] are present for the (later) device-flow login layer.
 */
@Serializable
data class Profile(
    val crsUrl: String,
    val keycloakIssuer: String? = null,
    val clientId: String? = null,
)

/**
 * Top-level config-file model.
 *
 * `defaultProfile` names the profile to use when neither `--env` nor an explicit `--crs-url` is
 * given. `profiles` maps a profile name to its [Profile].
 */
@Serializable
data class CrsctlConfig(
    val defaultProfile: String? = null,
    val profiles: Map<String, Profile> = emptyMap(),
) {
    companion object {
        val EMPTY = CrsctlConfig()
    }
}

/**
 * Loads (and centralizes the location of) the crsctl config file.
 *
 * Config-dir resolution lives in exactly ONE place ([configDir]) so adding Windows/Linux variants
 * later is purely additive. The chosen layout:
 *   - macOS:  ~/Library/Application Support/crsctl/config.json
 *   - other:  $XDG_CONFIG_HOME/crsctl/config.json, else ~/.config/crsctl/config.json
 *
 * A missing config file is tolerated and yields [CrsctlConfig.EMPTY].
 */
object ConfigLoader {

    private const val CONFIG_FILE_NAME = "config.json"

    /** The single source of truth for the per-OS config directory. */
    fun configDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        return when {
            osName.contains("mac") || osName.contains("darwin") ->
                Paths.get(home, "Library", "Application Support", "crsctl")

            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) {
                    Paths.get(xdg, "crsctl")
                } else {
                    Paths.get(home, ".config", "crsctl")
                }
            }
        }
    }

    fun configFile(): Path = configDir().resolve(CONFIG_FILE_NAME)

    /** Loads the config from the default location, tolerating absence. */
    fun load(): CrsctlConfig = load(configFile())

    /** Loads the config from [file], returning [CrsctlConfig.EMPTY] when the file is absent. */
    fun load(file: Path): CrsctlConfig {
        if (!Files.exists(file)) {
            return CrsctlConfig.EMPTY
        }
        val text = Files.readString(file)
        if (text.isBlank()) {
            return CrsctlConfig.EMPTY
        }
        return try {
            Json.decodeFromString(CrsctlConfig.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigLoadException("Config file $file is not valid crsctl config JSON: ${e.message}", e)
        }
    }
}

/** Thrown when a present config file cannot be parsed. */
class ConfigLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
