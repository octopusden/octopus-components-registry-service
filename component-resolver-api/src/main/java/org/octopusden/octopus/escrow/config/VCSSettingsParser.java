package org.octopusden.octopus.escrow.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.octopusden.octopus.escrow.model.VCSSettings;

import java.io.IOException;

public class VCSSettingsParser {

    public static final JsonDeserializer<VCSSettings> DESERIALIZER = new JsonDeserializer<VCSSettings>() {
        @Override
        public VCSSettings deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException {
            JsonNode treeNode = jsonParser.getCodec().readTree(jsonParser);
            return new VCSSettingsDeserializer().deserialize(treeNode);
        }
    };

    public VCSSettings parse(String rawSettings) throws IOException {
        return getObjectMapper().readValue(rawSettings, VCSSettings.class);
    }

    private ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(VCSSettings.class, DESERIALIZER);
        return objectMapper.registerModule(module);
    }
}
