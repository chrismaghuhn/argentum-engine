package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol

/**
 * Canonicalizes only the mana-cost representation used by explicit payment plans.
 *
 * A printed `{0}` parses as a generic zero symbol, while an effective zero cost produced by Rules
 * is [ManaCost.ZERO] with no symbols. The explicit payment contract must use the same form as the
 * authoritative validator. This helper deliberately does not change mana parsing, generic-cost
 * reduction, or any other Rules cost semantics.
 */
fun ManaCost.canonicalPaymentManaCost(): ManaCost {
    val soleSymbol = symbols.singleOrNull()
    return if (soleSymbol is ManaSymbol.Generic && soleSymbol.amount == 0) {
        ManaCost.ZERO
    } else {
        this
    }
}

/**
 * Renders the canonical mana cost used by the explicit payment wire contract. Unlike
 * [ManaCost.toString], this preserves the explicit `{0}` marker for an effective zero cost.
 */
fun ManaCost.canonicalPaymentManaCostWireString(): String =
    canonicalPaymentManaCost().let { canonical ->
        if (canonical == ManaCost.ZERO) "{0}" else canonical.toString()
    }
