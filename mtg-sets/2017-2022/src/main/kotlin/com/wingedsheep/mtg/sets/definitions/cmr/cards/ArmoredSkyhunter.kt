package com.wingedsheep.mtg.sets.definitions.cmr.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardOrder
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.SelectionRestriction
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Armored Skyhunter — Commander Legends #11
 * {3}{W} · Creature — Cat Knight · 3/3
 *
 * Whenever this creature attacks, look at the top six cards of your library. You may put an Aura
 * or Equipment card from among them onto the battlefield. If an Equipment is put onto the
 * battlefield this way, you may attach it to a creature you control. Put the rest of those cards
 * on the bottom of your library in a random order.
 */
val ArmoredSkyhunter = card("Armored Skyhunter") {
    manaCost = "{3}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Cat Knight"
    power = 3
    toughness = 3
    oracleText = "Flying\nWhenever this creature attacks, look at the top six cards of your library. " +
        "You may put an Aura or Equipment card from among them onto the battlefield. If an " +
        "Equipment is put onto the battlefield this way, you may attach it to a creature you " +
        "control. Put the rest of those cards on the bottom of your library in a random order."

    keywords(Keyword.FLYING)

    triggeredAbility {
        trigger = Triggers.self.attacks()
        effect = Effects.Pipeline {
            val looked = gather(CardSource.TopOfLibrary(DynamicAmounts.fixed(6)), name = "skyhunter_looked")
            val split = chooseUpToSplit(
                count = 1,
                from = looked,
                filter = GameObjectFilter.Enchantment.withSubtype("Aura") or
                    GameObjectFilter.Artifact.withSubtype("Equipment"),
                prompt = "You may put an Aura or Equipment card onto the battlefield",
                selectedLabel = "Put onto the battlefield",
                remainderLabel = "Put on the bottom of your library",
                showAllCards = true,
                restrictions = listOf(SelectionRestriction.AuraMustHaveLegalHost),
                name = "skyhunter_selected",
                remainderName = "skyhunter_rest",
            )
            move(split.selected, CardDestination.ToZone(Zone.BATTLEFIELD))
            ifNotEmpty(split.selected, filter = GameObjectFilter.Artifact.withSubtype("Equipment")) {
                run(
                    Effects.May(
                        effect = Effects.Pipeline {
                            val host = selectTarget(
                                TargetObject(filter = TargetFilter(GameObjectFilter.Creature.youControl())),
                                name = "skyhunter_host",
                            )
                            run(
                                Effects.AttachTargetEquipmentToCreature(
                                    equipmentTarget = split.selected.asTarget,
                                    creatureTarget = host.asTarget,
                                )
                            )
                        },
                        descriptionOverride = "You may attach it to a creature you control",
                    )
                )
            }
            move(
                split.remainder,
                CardDestination.ToZone(Zone.LIBRARY, placement = ZonePlacement.Bottom),
                order = CardOrder.Random,
            )
        }
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "11"
        artist = "Denman Rooke"
        imageUri = "https://cards.scryfall.io/normal/front/2/b/2bf63241-9091-42f5-b997-9ce5aa3484f1.jpg?1783928889"
        ruling(
            "2020-11-10",
            "An Aura put onto the battlefield this way doesn't target anything (so it could be " +
                "attached to an opponent's permanent with hexproof, for example), but the Aura's " +
                "enchant ability restricts what it can be attached to. If the Aura can't legally be " +
                "attached to anything, you can't choose to put it onto the battlefield at all."
        )
    }
}
