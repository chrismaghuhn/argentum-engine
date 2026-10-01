package com.wingedsheep.mtg.sets.definitions.cmd.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.references.Player.OwnerOf
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

/**
 * Chaos Warp — Commander 2011 #114 (canonical printing; later sets carry `Printing` rows).
 * {2}{R}
 * Instant
 *
 * The owner of target permanent shuffles it into their library, then reveals the
 * top card of their library. If it's a permanent card, they put it onto the
 * battlefield.
 */
val ChaosWarp = card("Chaos Warp") {
    manaCost = "{2}{R}"
    colorIdentity = "R"
    typeLine = "Instant"
    oracleText = "The owner of target permanent shuffles it into their library, then reveals the top card of their library. If it's a permanent card, they put it onto the battlefield."

    spell {
        val permanent = target(TargetFilter.Permanent)

        effect = Effects.Pipeline {
            run(Effects.ShuffleIntoLibrary(permanent))
            val revealed = gather(
                CardSource.TopOfLibrary(
                    count = 1,
                    player = OwnerOf("target permanent")
                ),
                revealed = true
            )
            val permanentCard = selectAll(from = revealed, filter = GameObjectFilter.Permanent)
            move(permanentCard, CardDestination.ToZone(Zone.BATTLEFIELD), underOwnersControl = true)
        }
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "114"
        artist = "Trevor Claxton"
        imageUri = "https://cards.scryfall.io/normal/front/0/4/042431bc-0b21-4920-802f-6dd02e4c8721.jpg?1783941212"
    }
}
