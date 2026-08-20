package com.wingedsheep.mtg.sets.definitions.conflux.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.Chooser
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.ShuffleLibraryEffect
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Path to Exile — Conflux #15
 * {W} · Instant
 *
 * Exile target creature. Its controller may search their library for a basic land card, put that
 * card onto the battlefield tapped, then shuffle.
 */
val PathToExile = card("Path to Exile") {
    manaCost = "{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Exile target creature. Its controller may search their library for a basic land card, put that card onto the battlefield tapped, then shuffle."

    spell {
        val target = target("target creature", Targets.Creature)
        effect = Effects.Pipeline {
            run(Effects.Exile(target))
            run(
                MayEffect(
                    Effects.Pipeline {
                        val searchable = gather(
                            CardSource.FromZone(
                                zone = Zone.LIBRARY,
                                player = Player.ControllerOf("target creature"),
                                filter = GameObjectFilter.BasicLand,
                            ),
                            name = "searchable",
                        )
                        val found = chooseUpTo(
                            count = 1,
                            from = searchable,
                            chooser = Chooser.ControllerOfTarget,
                            prompt = "Search your library for a basic land card",
                            name = "found",
                        )
                        move(
                            found,
                            CardDestination.ToZone(
                                zone = Zone.BATTLEFIELD,
                                player = Player.ControllerOf("target creature"),
                                placement = ZonePlacement.Tapped,
                            )
                        )
                        run(ShuffleLibraryEffect(EffectTarget.PlayerRef(Player.ControllerOf("target creature"))))
                    },
                    decisionMaker = EffectTarget.TargetController,
                    descriptionOverride = "Its controller may search their library for a basic land card, put that card onto the battlefield tapped, then shuffle.",
                )
            )
        }
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "15"
        artist = "Todd Lockwood"
        imageUri = "https://cards.scryfall.io/normal/front/2/9/29b7a8b1-b98e-483a-87a4-73bd831c03d4.jpg?1783942491"
    }
}
