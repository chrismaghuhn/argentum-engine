package com.wingedsheep.mtg.sets.definitions.c17.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.conditions.ComparisonOperator
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.ForEachInCollectionEffect
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Balan, Wandering Knight — Commander 2017 #2
 *
 * The activated ability gathers the source and all Equipment controlled by its controller into
 * explicit pipeline collections, then reuses the normal attachment effect once for each gathered
 * Equipment. No selection or implicit ordering decision is involved: the Oracle instruction names
 * every qualifying Equipment.
 */
val BalanWanderingKnight = card("Balan, Wandering Knight") {
    manaCost = "{2}{W}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Cat Knight"
    power = 3
    toughness = 3
    oracleText = "First strike\n" +
        "Balan has double strike as long as two or more Equipment are attached to it.\n" +
        "{1}{W}: Attach all Equipment you control to Balan."

    keywords(Keyword.FIRST_STRIKE)

    staticAbility {
        condition = Conditions.CompareAmounts(
            DynamicAmounts.equipmentAttachedToSelf(),
            ComparisonOperator.GTE,
            DynamicAmount.Fixed(2),
        )
        ability = GrantKeyword(Keyword.DOUBLE_STRIKE, GroupFilter.source())
    }

    activatedAbility {
        cost = Costs.Mana("{1}{W}")
        effect = Effects.Pipeline {
            val source = gather(CardSource.Self, name = "source")
            val equipment = gather(
                GameObjectFilter.Artifact.withSubtype(Subtype.EQUIPMENT),
                player = Player.You,
                name = "equipment",
            )
            run(
                ForEachInCollectionEffect(
                    collection = equipment.key,
                    effect = Effects.AttachTargetEquipmentToCreature(
                        equipmentTarget = EffectTarget.Self,
                        creatureTarget = EffectTarget.PipelineTarget(source.key),
                    ),
                )
            )
        }
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "2"
        artist = "Svetlin Velinov"
        flavorText = "\"What weapon will you bear against one who's mastered them all?\""
        imageUri = "https://cards.scryfall.io/normal/front/5/d/5d0b279c-9a6d-4a56-878e-0ebf3f609f65.jpg?1783935951"
        ruling(
            "2017-08-25",
            "Balan's activated ability has no timing restriction. You can activate it any time you " +
                "have priority."
        )
        ruling(
            "2017-08-25",
            "If Balan deals first-strike damage and then gains double strike (most likely because it " +
                "picked up some Equipment with its activated ability after first-strike damage was " +
                "dealt), it will also deal regular combat damage."
        )
    }
}
