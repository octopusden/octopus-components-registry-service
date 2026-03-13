ruleset {
    description 'CRS CodeNarc rules for Groovy validation'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/imports.xml') {
        MisorderedStaticImports(enabled: false)
    }
    ruleset('rulesets/unnecessary.xml') {
        UnnecessaryGString(enabled: false)
        UnnecessaryGetter(enabled: false)
        UnnecessaryReturnKeyword(enabled: false)
        UnnecessaryInstanceOfCheck(enabled: false)
        UnnecessarySemicolon(enabled: false)
    }
}
