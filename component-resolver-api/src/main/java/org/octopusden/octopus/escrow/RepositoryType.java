package org.octopusden.octopus.escrow;

public enum RepositoryType {
    CVS("HEAD", false),
    MERCURIAL("default", true),
    GIT("master", true);
    private final String defaultBranch;
    private final boolean caseInsensitive;

    RepositoryType(String defaultBranch, boolean caseInsensitive) {
        this.defaultBranch = defaultBranch;
        this.caseInsensitive = caseInsensitive;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean getCaseInsensitive() {
        return caseInsensitive;
    }
}
