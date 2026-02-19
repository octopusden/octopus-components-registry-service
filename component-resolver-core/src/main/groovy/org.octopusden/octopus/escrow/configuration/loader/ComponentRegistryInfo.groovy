package org.octopusden.octopus.escrow.configuration.loader

import org.apache.commons.lang3.Validate
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
        File file
        try {
            file = new File(url.toURI())
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Handle malformed URIs or special URL formats
            String urlPath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8.toString())
            // On Windows, remove leading slash from file:///C:/path format
            if (System.getProperty("os.name", "").toLowerCase().contains("win") &&
                urlPath.matches("^/[A-Za-z]:/.*")) {
                urlPath = urlPath.substring(1)
            }
            file = new File(urlPath)
        }

        String parentPath = file.getParent()
        if (parentPath == null) {
            throw new IllegalStateException("Cannot determine parent directory for config file URL: ${url}, resolved file: ${file.absolutePath}")
        }

        return createFromFileSystem(parentPath, file.getName())
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

    String getBasePath() {
        return basePath
    }

    String getMainConfigName() {
        return mainConfigName
    }

    boolean getFromClassPath() {
        return fromClassPath
    }

}
