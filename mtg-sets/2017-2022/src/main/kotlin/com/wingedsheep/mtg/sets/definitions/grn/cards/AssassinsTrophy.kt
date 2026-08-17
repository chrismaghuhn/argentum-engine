package com.wingedsheep.mtg.sets.definitions.grn.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.ForEachPlayerEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.SearchDestination
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Assassin's Trophy (GRN #152)
 * {B}{G}
 * Instant
 *
 * Destroy target permanent an opponent controls. Its controller may search their library for a
 * basic land card, put it onto the battlefield, then shuffle.
 */
val AssassinsTrophy = card("Assassin's Trophy") {
    manaCost = "{B}{G}"
    colorIdentity = "BG"
    typeLine = "Instant"
    oracleText = "Destroy target permanent an opponent controls. Its controller may search their " +
        "library for a basic land card, put it onto the battlefield, then shuffle."

    spell {
        val permanent = target(
            "target permanent an opponent controls",
            TargetPermanent(filter = TargetFilter.PermanentOpponentControls)
        )
        effect = Effects.Pipeline {
            run(Effects.Destroy(permanent))
            run(
                MayEffect(
                    ForEachPlayerEffect(
                        players = Player.ControllerOf("target permanent"),
                        effects = listOf(
                            Patterns.Library.searchLibrary(
                                filter = GameObjectFilter.BasicLand,
                                destination = SearchDestination.BATTLEFIELD,
                            )
                        ),
                    ),
                    decisionMaker = EffectTarget.TargetController,
                    descriptionOverride = "Its controller may search their library for a basic land " +
                        "card, put it onto the battlefield, then shuffle.",
                )
            )
        }
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "152"
        artist = "Seb McKinnon"
        imageUri = "https://cards.scryfall.io/normal/front/9/0/906b6e99-128f-4c11-8daf-16099d35b0d4.jpg?1783934143"
    }
}
