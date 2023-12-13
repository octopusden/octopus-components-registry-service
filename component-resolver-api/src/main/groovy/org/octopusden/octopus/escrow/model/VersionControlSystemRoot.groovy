package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import org.octopusden.octopus.escrow.RepositoryType
import org.apache.commons.lang3.StringUtils

class VersionControlSystemRoot {

    private final String name
    private final RepositoryType repositoryType
    private final String tag
    private final String vcsPath
    private final String rawBranch


    static VersionControlSystemRoot create(String name = "main", RepositoryType repositoryType, String vcsPath,
                                           String tag, String branch) {
        def vcsBranch = branch
        return new VersionControlSystemRoot(name, repositoryType,
                (repositoryType?.caseInsensitive) ? vcsPath?.toLowerCase() : vcsPath,
                tag, vcsBranch)
    }

    private VersionControlSystemRoot(String name, RepositoryType repositoryType, String vcsPath, String tag, String branch) {
        this.name = name
        this.repositoryType = repositoryType
        this.vcsPath = vcsPath
        this.tag = tag
        this.rawBranch = branch
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getTag() {
        return tag
    }

    String getVcsPath() {
        return vcsPath
    }

    //TODO: JsonIgnore?
    String getBranch() {
        return StringUtils.isNotBlank(rawBranch) ?
                rawBranch :
                repositoryType != null ? repositoryType.defaultBranch : null
    }

    String getRawBranch() {
        return rawBranch
    }

    RepositoryType getRepositoryType() {
        return repositoryType
    }

    String getName() {
        return name
    }

    @JsonIgnore
    boolean isFullyConfigured() {
        return repositoryType != null &&
                StringUtils.isNotBlank(vcsPath) &&
                StringUtils.isNotBlank(tag) &&
                StringUtils.isNotBlank(getBranch())
    }

    @Override
    String toString() {
        return "VCSRoot{" +
                "name='" + name + '\'' +
                ", type=" + repositoryType +
                ", tag='" + tag + '\'' +
                ", vcsPath='" + vcsPath + '\'' +
                ", branch='" + branch + '\'' +
                '}'
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        VersionControlSystemRoot that = (VersionControlSystemRoot) o

        if (branch != that.branch) return false
        if (repositoryType != that.repositoryType) return false
        if (tag != that.tag) return false
        if (vcsPath != that.vcsPath) return false
        if (name != that.name) return false

        return true
    }

    int hashCode() {
        int result
        result = (repositoryType != null ? repositoryType.hashCode() : 0)
        result = 31 * result + (tag != null ? tag.hashCode() : 0)
        result = 31 * result + (vcsPath != null ? vcsPath.hashCode() : 0)
        result = 31 * result + (name != null ? name.hashCode() : 0)
        result = 31 * result + (branch != null ? branch.hashCode() : 0)
        return result
    }
}
