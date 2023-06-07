package org.octopusden.octopus.crs.resolver.core.converter;

import java.util.function.Function;

public class AbstractConverter<L, R> {
    private final Function<L, R> fromToFunction;

    public AbstractConverter(final Function<L, R> fromToFunction) {
        this.fromToFunction = fromToFunction;
    }

    public final R convertFrom(final L source) {
        return fromToFunction.apply(source);
    }
}
