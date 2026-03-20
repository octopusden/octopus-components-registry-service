package org.octopusden.octopus.components.registry.light.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.octopusden.octopus.components.registry.light.client.dto.ArtifactComponentsDTO;
import org.octopusden.octopus.components.registry.light.client.dto.ArtifactDependency;
import org.octopusden.octopus.components.registry.light.client.impl.ClassicComponentsRegistryServiceClient;
import org.octopusden.octopus.components.registry.light.client.impl.ClassicComponentsRegistryServiceClientUrlProvider;
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir;
import static org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest.getTestResourcesPath;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ComponentRegistryServiceApplication.class
)
@ActiveProfiles({"common", "test"})
@ResourceLock(value = "SYSTEM_PROPERTIES")
class ComponentsRegistryServiceLightClientTest {

    ComponentsRegistryServiceLightClientTest() {
        configureSpringAppTestDataDir();
    }

    private final Path testDataDir = getTestResourcesPath();
    protected ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    private ComponentsRegistryServiceClient componentsRegistryClient;

    @BeforeAll
    void startupServer() {
        componentsRegistryClient = new ClassicComponentsRegistryServiceClient(
                new ClassicComponentsRegistryServiceClientUrlProvider() {
                    @Override
                    public String getApiUrl() {
                        return "http://localhost:" + port;
                    }
                }
        );
    }

    @Test
    void testFindArtifactComponentsByArtifacts() {
        Set<ArtifactDependency> artifacts = toObject(
                testDataDir.resolve("sub1-sub2-sub3-artifacts.json"),
                new TypeReference<Set<ArtifactDependency>>() {
                }
        );

        ArtifactComponentsDTO actualArtifactComponents = findArtifactComponentsByArtifacts(artifacts);
        ArtifactComponentsDTO expectedArtifactComponents = toObject(
                testDataDir.resolve("expected-data/sub1-sub2-sub3-artifact-components.json"),
                ArtifactComponentsDTO.class
        );

        Assertions.assertIterableEquals(
                expectedArtifactComponents.getArtifactComponents(),
                actualArtifactComponents.getArtifactComponents()
        );
    }

    private <T> T toObject(Path path, TypeReference<T> typeReference) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readValue(inputStream, typeReference);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T toObject(Path path, Class<T> javaClass) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readValue(inputStream, javaClass);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void testGetSupportedGroupIds() {
        Set<String> expected = new HashSet<>(Arrays.asList("org.octopusden.octopus", "io.bcomponent"));
        Set<String> actual = getSupportedGroupIds();
        Assertions.assertEquals(expected, actual);
    }

    @NotNull
    protected Set<String> getSupportedGroupIds() {
        return componentsRegistryClient.getSupportedGroupIds();
    }

    @NotNull
    protected ArtifactComponentsDTO findArtifactComponentsByArtifacts(@NotNull Set<ArtifactDependency> artifacts) {
        return componentsRegistryClient.findArtifactComponentsByArtifacts(artifacts);
    }
}