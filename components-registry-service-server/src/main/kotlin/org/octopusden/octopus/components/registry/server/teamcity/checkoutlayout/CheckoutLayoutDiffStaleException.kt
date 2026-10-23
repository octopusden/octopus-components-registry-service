package org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout

/**
 * A Sync named a `diffId` that is not the last completed Diff (or no Diff has completed yet).
 * Keep the message: a Portal from before the checkout-layout rename detects a stale Diff by it.
 */
class CheckoutLayoutDiffStaleException : RuntimeException("diff replaced, re-run Diff") {
    companion object {
        const val ERROR_CODE = "checkout-layout-diff-stale"
    }
}
