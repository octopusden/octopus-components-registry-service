package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.ReleaseInfo
import org.octopusden.octopus.escrow.model.Tool
import org.junit.Test

import static org.octopusden.octopus.escrow.TestConfigUtils.escrowConfigurationLoader
import static org.octopusden.octopus.releng.dto.ComponentVersion.create

class ToolsInfoResolverTest {
    public static final String TEST_VERSION = "1.12.34-0056"
    ReleaseInfoResolver resolver;

    private ReleaseInfoResolver withConfigResolver(String config) {
        resolver = new ReleaseInfoResolver(
                escrowConfigurationLoader(config.endsWith(".groovy") ? config : config + ".groovy"))
        return resolver
    }

    @Test
    public void testDefaultTag() {
        ReleaseInfo info = withConfigResolver("bcomponent").resolveRelease(create("bcomponent", TEST_VERSION));
        Tool buildEnv = new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV")
        Tool androidSDK = new Tool(name: "AndroidSdk", escrowEnvironmentVariable: "AndroidSdk", targetLocation: "tools/AndroidSdk",
                sourceLocation: "env.AndroidSdk", installScript: "androidSdkInstaller")
        assert info.buildParameters.tools == [buildEnv, androidSDK]


    }
}
