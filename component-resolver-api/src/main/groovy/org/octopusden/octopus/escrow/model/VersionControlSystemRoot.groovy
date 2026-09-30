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
    private final String hotfixBranch
    private final String sourcePath
    private final String checkoutDirectory

    static VersionControlSystemRoot create(String name = "main", RepositoryType repositoryType, String vcsPath,
                                           String tag, String branch, String hotfixBranch) {
        return create(name, repositoryType, vcsPath, tag, branch, hotfixBranch, null, null)
    }

    /** Placement (ONB-001) comes from the database only; the Groovy DSL leaves it null. */
    static VersionControlSystemRoot create(String name, RepositoryType repositoryType, String vcsPath,
                                           String tag, String branch, String hotfixBranch,
                                           String sourcePath, String checkoutDirectory) {
        def vcsBranch = branch
        return new VersionControlSystemRoot(name, repositoryType,
                (repositoryType?.isCaseSensitive() == false) ? vcsPath?.toLowerCase() : vcsPath,
                tag, vcsBranch, hotfixBranch, sourcePath, checkoutDirectory)
    }

    private VersionControlSystemRoot(String name, RepositoryType repositoryType, String vcsPath, String tag, String branch, String hotfixBranch,
                                     String sourcePath, String checkoutDirectory) {
        this.name = name
        this.repositoryType = repositoryType
        this.vcsPath = vcsPath
        this.tag = tag
        this.rawBranch = branch
        this.hotfixBranch = hotfixBranch
        this.sourcePath = sourcePath
        this.checkoutDirectory = checkoutDirectory
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getTag() {
        return tag
    }

    String getVcsPath() {
        return vcsPath
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getBranch() {
        return StringUtils.isNotBlank(rawBranch) ?
                rawBranch :
                repositoryType != null ? repositoryType.defaultBranch : null
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getHotfixBranch() {
        return hotfixBranch
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getSourcePath() {
        return sourcePath
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getCheckoutDirectory() {
        return checkoutDirectory
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
                ", hotfixBranch='" + hotfixBranch + '\'' +
                ", sourcePath='" + sourcePath + '\'' +
                ", checkoutDirectory='" + checkoutDirectory + '\'' +
                '}'
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        VersionControlSystemRoot that = (VersionControlSystemRoot) o

        return Objects.equals(hotfixBranch, that.hotfixBranch) &&
                Objects.equals(branch, that.branch) &&
                Objects.equals(repositoryType, that.repositoryType) &&
                Objects.equals(vcsPath, that.vcsPath) &&
                Objects.equals(tag, that.tag) &&
                Objects.equals(name, that.name) &&
                Objects.equals(sourcePath, that.sourcePath) &&
                Objects.equals(checkoutDirectory, that.checkoutDirectory)
    }

    int hashCode() {
        return Objects.hash(name, repositoryType, tag, vcsPath, branch, hotfixBranch, sourcePath, checkoutDirectory)
    }
}
