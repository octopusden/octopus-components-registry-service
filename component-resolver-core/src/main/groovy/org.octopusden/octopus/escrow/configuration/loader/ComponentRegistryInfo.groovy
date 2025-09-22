package org.octopusden.octopus.escrow.configuration.loader

import org.apache.commons.lang3.Validate

class ComponentRegistryInfo {

    static ComponentRegistryInfo createFromFileSystem(String basePath, String mainConfigName) {
        return new ComponentRegistryInfo(basePath, mainConfigName, false);
    }

    static ComponentRegistryInfo fromClassPath(String basePath, String mainConfigName) {
        return new ComponentRegistryInfo(basePath, mainConfigName, true);
    }

    static ComponentRegistryInfo fromClassPath(String mainConfigName) {
        return new ComponentRegistryInfo(".", mainConfigName, true);
    }

    static ComponentRegistryInfo createFromURL(URL url) {
        File file = new File(url.getFile());
        return createFromFileSystem(file.getParent(), file.getName());
    }

    private ComponentRegistryInfo(String basePath, String mainConfigName, boolean fromClassPath) {
        Validate.notNull(basePath, "basePath can't be null")
        Validate.notNull(mainConfigName, "mainConfigName can't be null")
        this.basePath = basePath
        this.mainConfigName = mainConfigName
        this.fromClassPath = fromClassPath;
    }

    private final String basePath

    private final String mainConfigName

    private boolean fromClassPath = false;

    private final String distributionValidationExclusionsFile = "validation/exclusions-distribution.txt"

    String getBasePath() {
        return basePath
    }

    String getMainConfigName() {
        return mainConfigName
    }

    boolean getFromClassPath() {
        return fromClassPath
    }

    String getDistributionValidationExclusionsFile() {
        return distributionValidationExclusionsFile
    }

}
