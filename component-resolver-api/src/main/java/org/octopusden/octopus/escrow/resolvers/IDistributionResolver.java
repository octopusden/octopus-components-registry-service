package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.releng.dto.ComponentVersion;

public interface IDistributionResolver {
    Distribution resolveDistribution(ComponentVersion componentVersion);
}
