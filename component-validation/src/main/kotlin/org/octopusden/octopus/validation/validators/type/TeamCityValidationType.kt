package org.octopusden.octopus.validation.validators.type

import org.octopusden.octopus.validation.core.ValidationType

/** The TeamCity project questions. */
enum class TeamCityValidationType : ValidationType {
    /** Is any build configuration attached to default build template? */
    ATTACHED_TO_BUILD_TEMPLATE,

    /** Does the project override the default build step inherited from build template */
    OVERRIDES_DEFAULT_BUILD_STEP,

    /** Is there an uninherited (custom) build step that resolves to a Java or Maven version? */
    HAS_CUSTOM_BUILD_STEP,

    /** Does any uninherited build step, or the default build step, resolve to Java 1.8? */
    USES_OLD_JAVA_VERSION,

    /** Do the uninherited build steps and the default build step resolve to more than one distinct Java version? */
    MULTIPLE_JAVA_VERSIONS,

    /** Do the uninherited build steps and the default build step resolve to more than one distinct Maven version? */
    MULTIPLE_MAVEN_VERSIONS,

    /**
     * Does any build step resolve a Java version whose java-home does NOT go through the standard
     * `%env.JAVA_HOME%` reference?
     */
    JAVA_HOME_NOT_FROM_ENV,

    /**
     * Do the compile build configurations attach VCS roots that differ from the component's registry
     * roots? Needs registry data, so the server evaluates it (not a module validator).
     */
    VCS_ROOTS_DIFFER_FROM_REGISTRY,
    ;

    override val id get() = name
}
