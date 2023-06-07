package org.octopusden.octopus.escrow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.octopusden.octopus.escrow.config.VCSSettingsParser;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class VCSSettingsParserTest {

    private static final VCSSettingsParser PARSER = new VCSSettingsParser();

    @Test
    public void testParseEmptySettingsShouldBeCorrect() throws IOException {
        VCSSettings empty = VCSSettings.createEmpty();
        assertEquals(empty, PARSER.parse(serialize(empty)));
    }

    @Test
    public void testParseComplexVCSSettingsShouldBeCorrect() throws IOException {
        VCSSettings settings = TestHelper.createTestVCSSettings();
        assertEquals(settings, PARSER.parse(serialize(settings)));
    }

    @Test
    public void testParseVCSSettingsOnlyWithNameShouldBeCorrect() throws IOException {
        VCSSettings settings = VCSSettings.create("componentc_db");
        assertEquals(settings, PARSER.parse(serialize(settings)));
    }

    @Test
    public void testParseVCSSettingsAndRootsOnlyWithNameShouldBeCorrect() throws IOException {
        VCSSettings settings = VCSSettings.create("componentc_db", TestHelper.createTestVCSSettings().getVersionControlSystemRoots());
        assertEquals(settings, PARSER.parse(serialize(settings)));
    }

    private String serialize(VCSSettings settings) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(settings);
    }
}
