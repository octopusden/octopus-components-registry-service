package org.octopusden.octopus.escrow.dto;

import org.octopusden.releng.versions.IVersionInfo;

import java.util.function.Function;

public class SimpleExpressionContext {
    private final String version;
    private final String component;
    private final IVersionInfo versionInfo;

    public SimpleExpressionContext(String component, String version, Function<String, IVersionInfo> versionItemExtractor) {
        this.component = component;
        this.version = version;
        versionInfo = versionItemExtractor.apply(version);
    }

    public String getVersion() {
        return version;
    }

    public String getComponent() {
        return component;
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