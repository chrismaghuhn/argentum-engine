package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CollectionFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Ardenn, Intrepid Archaeologist (Commander Legends #10).
 *
 * Partner is retained in the Oracle text, but is not modelled as a keyword because the engine's
 * Commander setup currently supports one commander per player. The attachment ability composes
 * the generic collection filter, explicit any-number selection, and batch attach primitive.
 */
val ArdennIntrepidArchaeologist = card("Ardenn, Intrepid Archaeologist") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Kor Scout"
    oracleText = "At the beginning of combat on your turn, you may attach any number of Auras and " +
        "Equipment you control to target permanent or player.\n" +
        "Partner (You can have two commanders if both have partner.)"
    power = 2
    toughness = 2

    triggeredAbility {
        trigger = Triggers.BeginCombat
        optional = true
        target = Targets.PermanentOrPlayer
        effect = Effects.Pipeline {
            val candidates = gather(
                source = CardSource.ControlledPermanents(
                    player = Player.You,
                    filter = GameObjectFilter.Enchantment.withSubtype("Aura") or
                        GameObjectFilter.Artifact.withSubtype("Equipment"),
                ),
                name = "ardenn_candidates",
            )
            val legal = filter(
                candidates,
                CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
                name = "ardenn_legal",
            )
            val selected = chooseAnyNumber(
                from = legal,
                prompt = "Choose any number of Auras and Equipment to attach",
                alwaysPrompt = true,
                name = "ardenn_selected",
            )
            attach(selected, EffectTarget.ContextTarget(0))
        }
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "10"
        artist = "Jason Rainville"
        flavorText = "\"With the right tools, even the sky is within reach.\""
        imageUri = "https://cards.scryfall.io/normal/front/7/2/728b802c-969b-4865-b7a0-871c585d097a.jpg?1783928890"
    }
}
