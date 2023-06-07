package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.escrow.config.JiraParametersResolverConfig;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = JiraParametersResolverConfig.class)
@TestPropertySource(locations = "classpath:test.properties")
public class JiraParametersResolverWithConfigTest {

    @Autowired
    public IJiraParametersResolver componentInfoResolver;


    @Test
    public void testPatchComponents() {
        componentInfoResolver.reloadComponentsRegistry();
        ComponentVersion componentVersion = ComponentVersion.create("commoncomponent", "11.2");
        JiraComponent component = componentInfoResolver.resolveComponent(componentVersion);
        assertNotNull("Component " + componentVersion + " Not Found", component);
        assertEquals("clientCustomerNameBank", component.getDisplayName());
    }
}
