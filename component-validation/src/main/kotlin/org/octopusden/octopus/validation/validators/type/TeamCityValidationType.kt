package org.octopusden.octopus.validation.validators.type

import org.octopusden.octopus.validation.core.ValidationType

/** The six TeamCity project questions. */
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
    ;

    override val id get() = name
}
