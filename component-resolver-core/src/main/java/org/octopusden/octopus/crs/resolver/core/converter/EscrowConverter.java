package org.octopusden.octopus.crs.resolver.core.converter;

import org.octopusden.octopus.components.registry.api.beans.EscrowBean;
import org.octopusden.octopus.components.registry.api.escrow.Escrow;
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig;

public final class EscrowConverter extends AbstractConverter<EscrowModuleConfig, Escrow> {
    public EscrowConverter() {
        super(escrowModuleConfig -> {
            final EscrowBean escrow = new EscrowBean();
            escrow.setGeneration(escrowModuleConfig.getEscrow().getGeneration().orElse(null));
            return escrow;
        });
    }
}
