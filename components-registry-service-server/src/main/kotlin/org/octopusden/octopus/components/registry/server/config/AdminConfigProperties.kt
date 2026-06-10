package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Code-as-config source for the two admin configuration blobs that used to be
 * admin-edited in the database (`registry_config['field-config']` and
 * `['component-defaults']`).
 *
 * Both subtrees are now declared in `service-config` (Spring Cloud Config) and
 * bound here:
 *  - `components-registry.field-config.<section>.<field>.{visibility,searchable,required,defaultValue,label,description}`
 *  - `components-registry.component-defaults.*`
 *
 * The class is intentionally **mutable** (no constructor binding): Spring Cloud's
 * `ConfigurationPropertiesRebinder` rebinds mutable `@ConfigurationProperties`
 * beans in place on `EnvironmentChangeEvent` (fired by `ContextRefresher.refresh()`
 * before `RefreshScopeRefreshedEvent`), so `POST /admin/reload-config` picks up a
 * fresh profile without a pod restart. `ConfigSyncService` then serializes these
 * beans into the legacy `Map<String, Any?>` blob shape and upserts the DB cache.
 *
 * This bean is harmless in no-db mode (plain data holder); only the writer
 * (`ConfigSyncService`) is `@ConditionalOnDatabaseEnabled`.
 *
 * Shares the `components-registry` prefix with [ComponentsRegistryProperties];
 * each bean binds only its own declared fields (unknown keys ignored), so the two
 * coexist without interference.
 */
@ConfigurationProperties(prefix = "components-registry")
class AdminConfigProperties {
    /** section ("component"/"build"/"jira"/"vcs") -> field -> entry. */
    var fieldConfig: MutableMap<String, MutableMap<String, FieldEntry>> = mutableMapOf()

    var componentDefaults: ComponentDefaults = ComponentDefaults()

    /** Per-field UI policy. All optional; absent fields default to `editable` in `FieldConfigService`. */
    class FieldEntry {
        /** `editable` | `readonly` | `hidden`. */
        var visibility: String? = null

        /** `Main` | `Extended` | `None`. */
        var searchable: String? = null
        var required: Boolean? = null
        var defaultValue: String? = null

        /** UI display-label override; the Portal falls back to its hardcoded label when absent. */
        var label: String? = null

        /** Tooltip-description override; the Portal falls back to its hardcoded registry when absent. */
        var description: String? = null
    }

    /**
     * Default values for new components — full key set of the legacy
     * `migrateDefaults()` output (see [ImportServiceImpl][org.octopusden.octopus.components.registry.server.service.impl.ImportServiceImpl]).
     */
    class ComponentDefaults {
        var buildSystem: String? = null
        var buildFilePath: String? = null
        var artifactIdPattern: String? = null
        var groupIdPattern: String? = null
        var componentDisplayName: String? = null
        var system: String? = null
        var clientCode: String? = null
        var parentComponent: String? = null
        var releasesInDefaultBranch: Boolean? = null
        var solution: Boolean? = null
        var archived: Boolean? = null
        var copyright: String? = null
        var labels: MutableList<String> = mutableListOf()
        var deprecated: Boolean? = null
        var octopusVersion: String? = null
        var build: Build? = null
        var jira: Jira? = null
        var distribution: Distribution? = null
        var vcs: Vcs? = null
        var escrow: Escrow? = null
        var doc: Doc? = null
    }

    class Build {
        var javaVersion: String? = null
        var mavenVersion: String? = null
        var gradleVersion: String? = null
        var requiredProject: Boolean? = null
        var projectVersion: String? = null
        var systemProperties: MutableMap<String, String> = mutableMapOf()
        var buildTasks: String? = null
    }

    class Jira {
        var projectKey: String? = null
        var displayName: String? = null
        var technical: Boolean? = null
        var componentVersionFormat: ComponentVersionFormat? = null

        class ComponentVersionFormat {
            var majorVersionFormat: String? = null
            var releaseVersionFormat: String? = null
            var buildVersionFormat: String? = null
            var lineVersionFormat: String? = null
            var hotfixVersionFormat: String? = null
        }
    }

    class Distribution {
        var explicit: Boolean? = null
        var external: Boolean? = null
        var GAV: String? = null
        var DEB: String? = null
        var RPM: String? = null
        var docker: String? = null
        var securityGroups: SecurityGroups? = null

        class SecurityGroups {
            var read: String? = null
        }
    }

    class Vcs {
        var externalRegistry: String? = null
        var vcsPath: String? = null
        var repositoryType: String? = null
        var tag: String? = null
        var branch: String? = null
    }

    class Escrow {
        var buildTask: String? = null
        var generation: String? = null
        var reusable: Boolean? = null
        var diskSpace: String? = null
        var providedDependencies: MutableList<String> = mutableListOf()
        var additionalSources: MutableList<String> = mutableListOf()
    }

    class Doc {
        var component: String? = null
        var majorVersion: String? = null
    }
}
