package org.octopusden.octopus.escrow.model

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.model.Dependencies
import groovy.transform.AutoClone
import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode(includeFields = true)
@AutoClone
class BuildParameters {
    private final String javaVersion
    private final String mavenVersion
    private final String gradleVersion
    private final boolean requiredProject
    private final String projectVersion
    private final String systemProperties
    private final String buildTasks
    private final List<Tool> tools
    Collection<BuildTool> buildTools = new ArrayList<>()
    Dependencies dependencies

    static BuildParameters create(String javaVersion, String mavenVersion, String gradleVersion, boolean requiredProject, String projectVersion, String systemProperties, String buildTasks, List<Tool> tools, Collection<BuildTool> buildTools) {
        return new BuildParameters(javaVersion, mavenVersion, gradleVersion, requiredProject, projectVersion, systemProperties, buildTasks, tools, buildTools)
    }

    private BuildParameters(String javaVersion, String mavenVersion, String gradleVersion, boolean requiredProject, String projectVersion, String systemProperties, String buildTasks, List<Tool> tools, Collection<BuildTool> buildTools) {
        this.javaVersion = javaVersion
        this.mavenVersion = mavenVersion
        this.gradleVersion = gradleVersion
        this.requiredProject = requiredProject
        this.systemProperties = systemProperties
        this.projectVersion = projectVersion
        this.tools = tools
        this.buildTasks = buildTasks
        if (buildTools != null) {
            this.buildTools.addAll(buildTools)
        }
    }

    List<Tool> getTools() {
        return tools
    }

    String getBuildTasks() {
        return buildTasks
    }


    String getJavaVersion() {
        return javaVersion
    }

    String getMavenVersion() {
        return mavenVersion
    }

    String getGradleVersion() {
        return gradleVersion
    }

    String getSystemProperties() {
        return systemProperties
    }

    boolean getRequiredProject() {
        return requiredProject
    }

    Map<String, String> getSystemPropertiesMap() {
        return systemProperties != null && systemProperties.trim().length() > 0 ? SystemPropertiesParser.parse(systemProperties) : [:]
    }

    String getprojectVersion() {
        return projectVersion
    }

    @Override
    String toString() {
        return "BuildParameters{ " +
                " javaVersion: " + javaVersion +
                " mavenVersion: " + mavenVersion +
                " gradleVersion: " + gradleVersion +
                ", requiredProject: " + requiredProject +
                ", projectVersion: " + projectVersion +
                ", systemProperties: " + systemProperties +
                ", buildTasks: " + buildTasks +
                ", tools:'" + tools + '\'' +
                '}';
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        BuildParameters that = (BuildParameters) o

        if (requiredProject != that.requiredProject) return false
        if (projectVersion != that.projectVersion) return false
        if (javaVersion != that.javaVersion) return false
        if (mavenVersion != that.mavenVersion) return false
        if (gradleVersion != that.gradleVersion) return false
        if (systemProperties != that.systemProperties) return false
        if (buildTasks != that.buildTasks) return false
        if (tools != null ? tools != that.tools : that.tools != null) return false


        return true
    }

    int hashCode() {
        int result
        result = (javaVersion != null ? javaVersion.hashCode() : 0)
        result = 31 * result + (requiredProject ? 1 : 0)
        result = 31 * result + (projectVersion != null ? projectVersion.hashCode() : 0)
        result = 31 * result + (systemProperties != null ? systemProperties.hashCode() : 0)
        result = 31 * result + (buildTasks != null ? buildTasks.hashCode() : 0)
        result = 31 * result + (tools != null ? tools.hashCode() : 0)

        return result
    }
}
