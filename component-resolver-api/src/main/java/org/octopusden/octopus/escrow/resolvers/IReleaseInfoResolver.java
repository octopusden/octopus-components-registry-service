package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.escrow.ReleaseInfo;
import org.octopusden.octopus.releng.dto.ComponentVersion;

import java.util.Map;

public interface IReleaseInfoResolver {
    ReleaseInfo resolveRelease(ComponentVersion componentVersion, Map<String, String> params);
    ReleaseInfo resolveRelease(ComponentVersion componentVersion);
}
