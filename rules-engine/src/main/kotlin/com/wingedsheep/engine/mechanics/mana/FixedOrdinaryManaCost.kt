package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol

/**
 * The payment-domain slice that can be represented without another controller choice.
 *
 * Substituting a value for X before calling this predicate would certify a different cost than
 * the action actually announces, so callers must apply it to the authoritative, unsubstituted
 * cost.
 */
fun ManaCost.isFixedOrdinaryManaCost(): Boolean =
    !hasX && symbols.all {
        it is ManaSymbol.Colored || it is ManaSymbol.Colorless || it is ManaSymbol.Generic
    }
