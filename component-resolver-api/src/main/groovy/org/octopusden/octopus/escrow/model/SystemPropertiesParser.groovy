package org.octopusden.octopus.escrow.model

import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import groovy.util.logging.Slf4j

@Slf4j
class SystemPropertiesParser {
    static Map<String, String> parse(String properties) {
        String[] items = properties.trim().split("\\s+")
        Map<String, String> map = [:]
        for (String item : items) {
            def parts = item.split("=", 2)
            if (parts.size() != 2) {
                throw new ComponentResolverException("Property $item is not parsed")
            }
            if (parts[0].size() < 3) {
                throw new ComponentResolverException("The property $item doesn't started from -[P,D]")
            }
            map.put(parts[0].substring(2), parts[1])
        }
        return map
    }
}
