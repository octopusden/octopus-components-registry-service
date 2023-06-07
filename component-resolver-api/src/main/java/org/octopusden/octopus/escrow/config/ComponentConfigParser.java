package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

public class ComponentConfigParser {


    public ComponentConfig parse(String componentConfigJson) throws IOException {
        ObjectMapper objectMapper = getObjectMapper();
        return objectMapper.readValue(componentConfigJson, ComponentConfig.class);
    }

    private ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        SimpleModule module = new SimpleModule();
        module.addDeserializer(ComponentConfig.class, new ComponentConfigDeserializer());
        objectMapper.registerModule(module);

        return objectMapper;
    }


}
