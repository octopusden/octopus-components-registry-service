package org.octopusden.octopus.escrow.dto;

import org.octopusden.releng.versions.IVersionInfo;
import org.octopusden.releng.versions.NumericVersionFactory;

import java.util.Map;
import java.util.function.Function;

public class EscrowExpressionContext extends VersionExpressionContext {
    private final Map<String, String> env = System.getenv();
    private final String fileName;
    private final String baseDir;
    private final String component;
    private final IVersionInfo versionInfo;

    public EscrowExpressionContext(String component, String version, String fileName, Function<String, IVersionInfo> versionItemExtractor) {
        super(version);
        this.fileName = fileName;
        this.component = component;
        versionInfo = versionItemExtractor.apply(version);
        baseDir = System.getProperty("user.dir");
    }

    public EscrowExpressionContext(String component, String version, String fileName, NumericVersionFactory numericVersionFactory) {
        this(component, version, fileName, numericVersionFactory::create);
    }

    //TODO: remove? is it used?
    public String getFileName() {
        return fileName;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public int getMajor() {
        return versionInfo.getMajor();
    }

    public int getMinor() {
        return versionInfo.getMinor();
    }

    public int getService() {
        return versionInfo.getService();
    }

    public int getFix() {
        return versionInfo.getFix();
    }

    public int getBuild() {
        return versionInfo.getBuildNumber();
    }

    public String getComponent() {
        return component;
    }

}