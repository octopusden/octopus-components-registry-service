ruleset {
    description 'CRS CodeNarc rules for Groovy validation'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/imports.xml') {
        // Import ordering churn is not worth blocking while Groovy code is in maintenance mode.
        MisorderedStaticImports(enabled: false)
    }
    ruleset('rulesets/unnecessary.xml') {
        // Legacy Groovy heavily relies on double-quoted literals without interpolation.
        UnnecessaryGString(enabled: false)
        // Groovy property/getter style is widespread in existing code and not a useful gate right now.
        UnnecessaryGetter(enabled: false)
        // Explicit return keeps old Groovy code readable enough; treating it as noise is intentional.
        UnnecessaryReturnKeyword(enabled: false)
        // Suspicious in theory, but current occurrences are deferred until we audit the surrounding logic.
        UnnecessaryInstanceOfCheck(enabled: false)
        // Semicolon cleanup is pure churn and adds no signal for this repository.
        UnnecessarySemicolon(enabled: false)
    }
}
