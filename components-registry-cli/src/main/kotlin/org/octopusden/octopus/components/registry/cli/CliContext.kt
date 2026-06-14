package org.octopusden.octopus.components.registry.cli

import org.octopusden.octopus.components.registry.cli.auth.AuthRequiredException
import org.octopusden.octopus.components.registry.cli.auth.CommandRunner
import org.octopusden.octopus.components.registry.cli.auth.CredentialStore
import org.octopusden.octopus.components.registry.cli.auth.DeviceFlowClient
import org.octopusden.octopus.components.registry.cli.auth.ProcessCommandRunner
import org.octopusden.octopus.components.registry.cli.auth.TokenManager
import org.octopusden.octopus.components.registry.cli.auth.credentialStore
import org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.config.ConfigLoader
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.config.EffectiveTarget
import org.octopusden.octopus.components.registry.cli.config.Profile
import org.octopusden.octopus.components.registry.cli.config.ResolverInputs
import org.octopusden.octopus.components.registry.cli.config.TargetResolver
import org.octopusden.octopus.components.registry.cli.output.OutputFormat

/**
 * Builds a [CrsClient] for a resolved [EffectiveTarget]. Pulled behind an interface so tests can
 * inject a client backed by a fake [org.octopusden.octopus.components.registry.cli.client.HttpExchange]
 * without going through real config / network.
 */
fun interface CrsClientFactory {
    fun create(target: EffectiveTarget): CrsClient
}

/**
 * The keycloak issuer + public client id resolved from the active profile, needed by the device-flow
 * login / logout / refresh paths. Both are SPIKE OUTPUTS (Part C): a profile that predates the auth
 * layer will not have them.
 */
data class OidcConfig(
    val issuer: String,
    val clientId: String,
)

/**
 * Shared state threaded from the root command down to every subcommand via Clikt's
 * `findOrSetObject`. Holds the parsed global options plus the seams (config loader, client factory,
 * command runner, device-flow client) that the target-resolution and auth helpers need.
 *
 * The seams default to production behaviour (load the real config file, build a real [CrsClient], run
 * the real `security` CLI, hit the real network); tests override them so commands run fully offline.
 */
class CliContext(
    val envFlag: String? = null,
    val crsUrlFlag: String? = null,
    val tokenFlag: String? = null,
    val output: OutputFormat = OutputFormat.TABLE,
    val verbose: Boolean = false,
    val insecureTokenStore: Boolean = false,
    val configLoader: () -> CrsctlConfig = { ConfigLoader.load() },
    val clientFactory: CrsClientFactory = CrsClientFactory { target ->
        CrsClient(baseUrl = target.crsUrl, token = target.token)
    },
    val commandRunner: CommandRunner = ProcessCommandRunner(),
    val deviceFlowClient: DeviceFlowClient = DeviceFlowClient(),
    private val getenv: (String) -> String? = System::getenv,
) {
    /**
     * Resolves the effective target from flags + `CRS_URL` / `CRS_TOKEN` env vars + the loaded
     * config (see [TargetResolver] for precedence). Throws
     * [org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException] when no URL
     * can be resolved.
     */
    fun resolveTarget(): EffectiveTarget =
        TargetResolver.resolve(
            ResolverInputs(
                crsUrlFlag = crsUrlFlag,
                tokenFlag = tokenFlag,
                envFlag = envFlag,
                crsUrlEnv = getenv("CRS_URL"),
                tokenEnv = getenv("CRS_TOKEN"),
            ),
            configLoader(),
        )

    /** Resolves the target and builds a [CrsClient] for it. */
    fun client(): CrsClient = clientFactory.create(resolveTarget())

    /**
     * Builds a [CrsClient] guaranteed to carry a bearer token, for endpoints that REQUIRE an
     * authenticated identity (e.g. the audit API behind ACCESS_AUDIT).
     *
     * Token resolution mirrors `whoami`:
     *   1. An explicit token (`--token` / `CRS_TOKEN`) — used verbatim.
     *   2. A stored refresh token exchanged for an access token via the [TokenManager].
     *
     * Unlike `whoami` (which treats "no credential" as anonymous), this throws
     * [AuthRequiredException] when neither path yields a token, so the caller never makes a blind
     * anonymous request that the server would reject with a generic 401. A missing profile-level
     * OIDC config ([ConfigResolutionException]) is likewise surfaced as [AuthRequiredException]:
     * from the user's point of view they simply are not logged in for this operation.
     */
    fun authedClient(): CrsClient {
        val target = resolveTarget()
        val token = resolveBearerToken(target)
        return clientFactory.create(target.copy(token = token))
    }

    /**
     * Returns a usable bearer token for an auth-required operation, or throws [AuthRequiredException]
     * when none can be resolved. An explicit token wins; otherwise a stored refresh token is
     * exchanged via the [TokenManager].
     */
    private fun resolveBearerToken(target: EffectiveTarget): String {
        if (!target.token.isNullOrBlank()) {
            return target.token
        }
        return try {
            tokenManager().accessToken()
        } catch (e: AuthRequiredException) {
            throw AuthRequiredException(NOT_AUTHENTICATED_MESSAGE)
        } catch (e: ConfigResolutionException) {
            throw AuthRequiredException(NOT_AUTHENTICATED_MESSAGE)
        }
    }

    /** Builds a [CrsClient] for [target] using the configured factory. */
    fun clientFor(target: EffectiveTarget): CrsClient = clientFactory.create(target)

    /** The selected credential store (keychain by default, plaintext file with `--insecure-token-store`). */
    fun credentialStore(): CredentialStore = credentialStore(insecureTokenStore, commandRunner)

    /**
     * Resolves the keycloak issuer + public client id from the active profile, failing clearly when
     * the profile lacks them. The active profile is the one named by `--env`, else the config
     * `defaultProfile`. A bare `--crs-url` (no profile) cannot supply OIDC settings.
     */
    fun resolveOidcConfig(): OidcConfig {
        val config = configLoader()
        val profile = activeProfile(config)
            ?: throw ConfigResolutionException(
                "Login requires a configured profile with 'keycloakIssuer' and 'clientId'. " +
                    "Select one with --env or set a defaultProfile; --crs-url alone cannot provide OIDC settings.",
            )
        val issuer = profile.keycloakIssuer
        val clientId = profile.clientId
        if (issuer.isNullOrBlank() || clientId.isNullOrBlank()) {
            throw ConfigResolutionException(
                "Profile is missing 'keycloakIssuer' and/or 'clientId' (these are outputs of the " +
                    "Keycloak device-flow client setup). Add them to the profile and retry.",
            )
        }
        return OidcConfig(issuer = issuer, clientId = clientId)
    }

    /** Builds a [TokenManager] for the active profile's OIDC config + selected credential store. */
    fun tokenManager(): TokenManager {
        val oidc = resolveOidcConfig()
        return TokenManager(
            issuer = oidc.issuer,
            clientId = oidc.clientId,
            store = credentialStore(),
            deviceFlow = deviceFlowClient,
        )
    }

    private fun activeProfile(config: CrsctlConfig): Profile? {
        val name = sequenceOf(envFlag, config.defaultProfile).firstOrNull { !it.isNullOrBlank() }
            ?: return null
        return config.profiles[name]
    }

    companion object {
        /** Surfaced (exit AUTH_REQUIRED) when an auth-required command has no resolvable credential. */
        const val NOT_AUTHENTICATED_MESSAGE =
            "audit requires `crsctl login` and the ACCESS_AUDIT permission"
    }
}
