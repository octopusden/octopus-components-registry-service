package org.octopusden.octopus.components.registry.api.distribution.distribution;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.octopusden.octopus.components.registry.api.beans.FileDistributionEntityBean;
import org.octopusden.octopus.components.registry.api.beans.MavenArtifactDistributionEntityBean;
import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity;
import org.octopusden.octopus.components.registry.api.distribution.entities.FileDistributionEntity;
import org.octopusden.octopus.components.registry.api.distribution.entities.MavenArtifactDistributionEntity;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributionEntityTest {
    @Test
    void testDistributionEntity() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        final String text = mapper.writeValueAsString(new TestClass(new MavenArtifactDistributionEntityBean("a:b"), new FileDistributionEntityBean("file:///some-file")));
        final TestClass value = mapper.readValue(text, TestClass.class);
        assertEquals("a:b", ((MavenArtifactDistributionEntity) value.maven).getGav());
        assertEquals(new URI("file:///some-file"), ((FileDistributionEntity) value.file).getUri());
    }

    public static class TestClass {
        @JsonProperty
        private DistributionEntity file;

        @JsonProperty
        private DistributionEntity maven;

        public TestClass() {
        }

        public TestClass(MavenArtifactDistributionEntity maven, FileDistributionEntity file) {
            this.maven = maven;
            this.file = file;
        }
    }
}
