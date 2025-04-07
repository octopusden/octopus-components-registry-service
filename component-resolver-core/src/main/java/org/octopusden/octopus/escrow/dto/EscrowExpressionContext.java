package org.octopusden.octopus.escrow.dto;

import org.octopusden.releng.versions.IVersionInfo;
import org.octopusden.releng.versions.NumericVersionFactory;

import java.util.Map;
import java.util.function.Function;

public class EscrowExpressionContext extends SimpleExpressionContext {
    private final Map<String, String> env = System.getenv();
    private final String fileName;
    private final String baseDir;

    public EscrowExpressionContext(String component, String version, String fileName, Function<String, IVersionInfo> versionItemExtractor) {
        super(component, version, versionItemExtractor);
        this.fileName = fileName;
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
}