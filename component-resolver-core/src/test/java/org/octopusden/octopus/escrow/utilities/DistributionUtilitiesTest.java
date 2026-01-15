package org.octopusden.octopus.escrow.utilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.octopusden.octopus.escrow.dto.DistributionEntity;
import org.octopusden.octopus.escrow.dto.FileDistributionEntity;
import org.octopusden.octopus.escrow.dto.MavenArtifactDistributionEntity;
import java.util.Collection;

public class DistributionUtilitiesTest {

    @Test
    void testValidMavenGAV() {
        Collection<DistributionEntity> result = DistributionUtilities.parseDistributionGAV("group:artifact:1.0,group2:artifact2:2.0");

        Assertions.assertEquals(2, result.size());
        result.forEach(e -> Assertions.assertTrue(e instanceof MavenArtifactDistributionEntity));
    }

    @Test
    void testValidFileDistributionEntity() {
        Collection<DistributionEntity> result = DistributionUtilities.parseDistributionGAV("file:/opt/dist/file.zip");

        Assertions.assertEquals(1, result.size());
        Assertions.assertTrue(result.iterator().next() instanceof FileDistributionEntity);
    }

    @Test
    void testInvalidGAV() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> DistributionUtilities.parseDistributionGAV("invalid"));

        Assertions.assertTrue(ex.getMessage().contains("Invalid GAV entry"), "Exception message should indicate invalid GAV entry");
    }

    @Test
    void testNullStringEntry() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> DistributionUtilities.parseDistributionGAV("a:b:1.0,null"));

        Assertions.assertTrue(ex.getMessage().contains("Invalid GAV entry"), "Exception message should indicate invalid GAV entry");
    }

    @Test
    void testTrailingComma() {
        Collection<DistributionEntity> result = DistributionUtilities.parseDistributionGAV("a:b:1.0,");

        Assertions.assertEquals(1, result.size());
    }

    @Test
    void testTrailingColon() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> DistributionUtilities.parseDistributionGAV("a:b:"));

        Assertions.assertTrue(ex.getMessage().contains("Invalid GAV entry"), "Exception message should indicate invalid GAV entry");
    }

    @Test
    void testEmptyGavEntryAfterSeparator() {
        Collection<DistributionEntity> result = DistributionUtilities.parseDistributionGAV("a:b:1.0,,");

        Assertions.assertEquals(1, result.size());
    }

    @Test
    void testNoPathForFile() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> DistributionUtilities.parseDistributionGAV("file:/"));

        Assertions.assertTrue(ex.getMessage().contains("Invalid GAV entry"), "Exception message should indicate invalid GAV entry");
    }
}
