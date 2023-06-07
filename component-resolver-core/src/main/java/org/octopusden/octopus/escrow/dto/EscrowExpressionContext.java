package org.octopusden.octopus.escrow.dto;

import org.octopusden.releng.versions.IVersionInfo;
import org.octopusden.releng.versions.NumericVersion;

import java.util.Map;
import java.util.function.Function;

public class EscrowExpressionContext {
    private final Map<String, String> env = System.getenv();
    private final String fileName;
    private final String version;
    private String component;
    private String baseDir;
    private IVersionInfo versionInfo;
    private static final EscrowExpressionContext VALIDATION_CONTEXT = new EscrowExpressionContext("zenit", "1984", "gold-medal.zip");


    public EscrowExpressionContext(String component, String version, String fileName, Function<String, IVersionInfo> versionItemExtractor) {
        this.component = component;
        this.fileName = fileName;
        this.version = version;
        baseDir = System.getProperty("user.dir");
        versionInfo = versionItemExtractor.apply(version);
    }

    public EscrowExpressionContext(String component, String version, String fileName) {
        this(component, version, fileName, componentVersion -> NumericVersion.parse(componentVersion));
    }

    public static EscrowExpressionContext getValidationEscrowExpressionContext() {
        return VALIDATION_CONTEXT;
    }


    public String getFileName() {
        return fileName;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public String getComponent() {
        return component;
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
}