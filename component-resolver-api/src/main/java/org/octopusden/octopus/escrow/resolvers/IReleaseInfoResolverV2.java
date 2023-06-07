package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.escrow.ReleaseInfo;
import org.octopusden.octopus.releng.dto.ComponentVersion;

public interface IReleaseInfoResolverV2 {
    ReleaseInfo resolveRelease(ComponentVersion componentVersion);
}
