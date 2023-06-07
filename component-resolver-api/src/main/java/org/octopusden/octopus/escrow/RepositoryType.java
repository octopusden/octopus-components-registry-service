package org.octopusden.octopus.escrow;

public enum RepositoryType {
    CVS("HEAD"),
    MERCURIAL("default"),
    GIT("master");
    private final String defaultBranch;

    RepositoryType(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }
}
