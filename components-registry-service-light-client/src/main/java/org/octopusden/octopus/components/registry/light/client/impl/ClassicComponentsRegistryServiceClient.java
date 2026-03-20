package org.octopusden.octopus.components.registry.light.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import feign.Feign;
import feign.Logger;
import feign.httpclient.ApacheHttpClient;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.slf4j.Slf4jLogger;
import org.octopusden.octopus.components.registry.light.client.ComponentsRegistryServiceErrorDecoder;
import org.octopusden.octopus.components.registry.light.client.dto.ArtifactComponentsDTO;
import org.octopusden.octopus.components.registry.light.client.dto.ArtifactDependency;
import org.octopusden.octopus.components.registry.light.client.ComponentsRegistryServiceClient;

import java.util.Set;

public class ClassicComponentsRegistryServiceClient implements ComponentsRegistryServiceClient {

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = configureObjectMapper(new ObjectMapper());

    private final ObjectMapper objectMapper;
    private ComponentsRegistryServiceClient client;

    public ClassicComponentsRegistryServiceClient(ClassicComponentsRegistryServiceClientUrlProvider apiUrlProvider) {
        this(apiUrlProvider, DEFAULT_OBJECT_MAPPER);
    }

    public ClassicComponentsRegistryServiceClient(
            ClassicComponentsRegistryServiceClientUrlProvider apiUrlProvider,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = createClient(apiUrlProvider.getApiUrl(), objectMapper);
    }

    @Override
    public ArtifactComponentsDTO findArtifactComponentsByArtifacts(Set<ArtifactDependency> artifacts) {
        return client.findArtifactComponentsByArtifacts(artifacts);
    }

    @Override
    public Set<String> getSupportedGroupIds() {
        return client.getSupportedGroupIds();
    }

    public void setUrl(String apiUrl) {
        client = createClient(apiUrl, objectMapper);
    }

    private ComponentsRegistryServiceClient createClient(String apiUrl, ObjectMapper objectMapper) {
        return Feign.builder()
                .client(new ApacheHttpClient())
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .errorDecoder(new ComponentsRegistryServiceErrorDecoder(objectMapper))
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .logger(new Slf4jLogger(ComponentsRegistryServiceClient.class))
                .logLevel(Logger.Level.FULL)
                .target(ComponentsRegistryServiceClient.class, apiUrl);
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        SimpleModule module = new SimpleModule();
        objectMapper.registerModule(module);
        return objectMapper;
    }
}