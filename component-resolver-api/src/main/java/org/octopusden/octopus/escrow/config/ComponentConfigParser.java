package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.octopusden.releng.versions.VersionNames;

import java.io.IOException;

public class ComponentConfigParser {

    private final VersionNames versionNames;

    public ComponentConfigParser(VersionNames versionNames) {
        this.versionNames = versionNames;
    }

    public ComponentConfig parse(String componentConfigJson) throws IOException {
        ObjectMapper objectMapper = getObjectMapper();
        return objectMapper.readValue(componentConfigJson, ComponentConfig.class);
    }

    private ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        SimpleModule module = new SimpleModule();
        module.addDeserializer(ComponentConfig.class, new ComponentConfigDeserializer(versionNames));
        objectMapper.registerModule(module);

        return objectMapper;
    }


}
