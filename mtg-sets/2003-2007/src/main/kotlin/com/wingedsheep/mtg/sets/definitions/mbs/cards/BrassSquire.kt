package com.wingedsheep.mtg.sets.definitions.mbs.cards

import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.targets.TargetPermanent

/**
 * Brass Squire — Mirrodin Besieged #101
 * {3} · Artifact Creature — Myr · 1/3
 *
 * {T}: Attach target Equipment you control to target creature you control.
 */
val BrassSquire = card("Brass Squire") {
    manaCost = "{3}"
    colorIdentity = ""
    typeLine = "Artifact Creature — Myr"
    power = 1
    toughness = 3
    oracleText = "{T}: Attach target Equipment you control to target creature you control."

    activatedAbility {
        cost = com.wingedsheep.sdk.dsl.Costs.Tap
        val equipment = target(
            "target Equipment you control",
            TargetPermanent(
                filter = TargetFilter(GameObjectFilter.Artifact.withSubtype(Subtype.EQUIPMENT).youControl())
            )
        )
        val creature = target(
            "target creature you control",
            TargetCreature(filter = TargetFilter.CreatureYouControl)
        )
        effect = Effects.AttachTargetEquipmentToCreature(equipment, creature)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "101"
        artist = "Ryan Pancoast"
        imageUri = "https://cards.scryfall.io/normal/front/3/7/37928b90-ab31-4c73-99b2-fe31feb2afea.jpg?1783941370"
    }
}
