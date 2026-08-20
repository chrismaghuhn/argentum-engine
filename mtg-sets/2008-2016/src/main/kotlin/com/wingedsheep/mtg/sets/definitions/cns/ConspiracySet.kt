package com.wingedsheep.mtg.sets.definitions.cns

import com.wingedsheep.mtg.sets.discovery.CardDiscovery
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.MtgSet
import com.wingedsheep.sdk.model.Printing

/**
 * Conspiracy (2014)
 *
 * Scaffolded to hold cards whose earliest real printing is Conspiracy. Intentionally
 * incomplete relative to the official set.
 *
 * Set Code: CNS
 * Release Date: June 6, 2014
 */
object ConspiracySet : MtgSet {

    override val code = "CNS"
    override val displayName = "Conspiracy"
    override val releaseDate = "2014-06-06"
    override val sealedSupported = false
    override val incomplete = true

    override val cards: List<CardDefinition> by lazy {
        CardDiscovery.findIn(CARDS_PACKAGE)
    }

    override val printings: List<Printing> by lazy {
        CardDiscovery.findPrintingsIn(CARDS_PACKAGE)
    }

    private const val CARDS_PACKAGE = "com.wingedsheep.mtg.sets.definitions.cns.cards"
}
