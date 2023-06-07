package org.octopusden.octopus.escrow;

import java.util.Objects;

public final class JiraProjectVersion {
    private static final int MAGIC_NUMBER = 37;
    private final String projectKey;
    private final String version;

    private JiraProjectVersion(String projectKey, String version) {
        Objects.requireNonNull(projectKey, "projectKey can't be null");
        Objects.requireNonNull(version, "version can't be null");
        this.projectKey = projectKey;
        this.version = version;
    }

    public static JiraProjectVersion create(String jiraProjectKey, String version) {
        return new JiraProjectVersion(jiraProjectKey, version);
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getVersion() {
        return version;
    }

    public int hashCode() {
        return projectKey.hashCode() * MAGIC_NUMBER + version.hashCode();
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (getClass() != o.getClass()) {
            return false;
        }
        JiraProjectVersion other = (JiraProjectVersion) o;
        return projectKey.equals(other.projectKey) && version.equals(other.version);
    }

    @Override
    public String toString() {
        return projectKey + ":" + version;
    }
}
