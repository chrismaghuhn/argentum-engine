package com.wingedsheep.mtg.sets.definitions.cns

import com.wingedsheep.mtg.sets.discovery.CardDiscovery
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.MtgSet
import com.wingedsheep.sdk.model.Printing

/**
 * Conspiracy (2014).
 *
 * This scaffold currently contains only cards whose canonical earliest printing is Conspiracy.
 *
 * Set Code: CNS
 * Release Date: 2014-06-06
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
