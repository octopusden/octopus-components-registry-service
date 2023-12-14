package org.octopusden.octopus.escrow;

public enum RepositoryType {
    CVS("HEAD", true),
    MERCURIAL("default", false),
    GIT("master", false);
    private final String defaultBranch;
    private final boolean caseSensitive;

    RepositoryType(String defaultBranch, boolean caseSensitive) {
        this.defaultBranch = defaultBranch;
        this.caseSensitive = caseSensitive;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }
}
